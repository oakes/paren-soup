(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [cljs.tools.reader :refer [read *wrap-value-and-add-metadata?*]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.string :refer [escape split-lines join replace split]]
            [goog.events :as events]
            [rangy.core]
            [rangy.textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [parinfer.core])
  (:require-macros [schema.core :refer [defn with-fn-validation]]
                   [cljs.core.async.macros :refer [go]]))

(defn read-safe :- (maybe (either Any js/Error))
  "Returns either a form or an exception object, or nil if EOF is reached."
  [reader :- js/Object]
  (try
    (binding [*wrap-value-and-add-metadata?* true]
      (read reader false nil))
    (catch js/Error e e)))

(defn tag-list :- [{Keyword Any}]
  "Returns a list of maps describing each tag."
  ([token :- Any]
    (tag-list token 0 0 0 0))
  ([token :- Any
    parent-level :- Int
    parent-adjust :- Int
    parent-column :- Int
    parent-line :- Int]
    (flatten
      (cond
        ; an error
        (instance? js/Error token)
        [(assoc (.-data token) :message (.-message token) :error? true :level parent-level)]
        
        ; a key-value pair from a map
        (and (coll? token) (nil? (meta token)))
        (map #(tag-list % parent-level parent-adjust parent-column parent-line) token)
        
        ; a valid token
        :else
        (let [{:keys [line column end-line end-column wrapped?]} (meta token)
              value (if wrapped? (first token) token)]
          [; begin tag
           {:line line :column column :value value
            :line-range (range (inc line) (inc end-line))}
           (if (coll? value)
             (let [delimiter-size (if (set? value) 2 1)
                   new-level (+ parent-level
                                (if (not= parent-line line)
                                  parent-adjust
                                  0))
                   new-adjust (if (list? value) 2 delimiter-size)
                   new-column (max (dec column)
                                   parent-column
                                   0)]
               [; open delimiter tags
                {:line line :column column :delimiter? true}
                {:end-line line :end-column (+ column delimiter-size) :level (+ new-level new-adjust new-column)}
                ; child tags
                (map #(tag-list % new-level new-adjust new-column line) value)
                ; close delimiter tags
                {:line end-line :column (dec end-column) :delimiter? true}
                {:end-line end-line :end-column end-column}])
             [])
           ; end tag
           {:end-line end-line :end-column end-column :level (+ parent-level parent-adjust parent-column)}])))))

(defn escape-html :- Str
  [s :- Str]
  (escape s {\< "&lt;"
             \> "&gt;"
             \& "&amp;"
             \' "&apos;"}))

(defn tag->html :- Str
  "Returns an HTML string for the given tag description."
  [tag :- {Keyword Any}]
  (cond
    (:delimiter? tag) "<span class='delimiter'>"
    (:error? tag) (str "<span class='error' data-message='"
                       (some-> (:message tag) escape-html)
                       "'></span>")
    (:line tag) (let [value (:value tag)]
                  (cond
                    (symbol? value) "<span class='symbol'>"
                    (list? value) "<span class='collection list'>"
                    (vector? value) "<span class='collection vector'>"
                    (map? value) "<span class='collection map'>"
                    (set? value) "<span class='collection set'>"
                    (number? value) "<span class='number'>"
                    (string? value) "<span class='string'>"
                    (keyword? value) "<span class='keyword'>"
                    (nil? value) "<span class='nil'>"
                    (contains? #{true false} value) "<span class='boolean'>"
                    :else "<span>"))
    (:end-line tag) "</span>"
    :else "<span>"))

(defn line->html :- Str
  "Returns the given line with html added."
  [line :- Str
   tags-for-line :- [{Keyword Any}]]
  (let [get-column #(or (:column %) (:end-column %))
        tags-for-line (sort-by get-column tags-for-line)
        html-per-column (sequence (comp (partition-by get-column)
                                        (map #(join (map tag->html %))))
                                  tags-for-line)
        columns (set (map get-column tags-for-line))
        segments (loop [i 0
                        segments (transient [])
                        current-segment (transient [])]
                   (if-let [c (get line i)]
                     (if (contains? columns (inc i))
                       (recur (inc i)
                              (conj! segments (persistent! current-segment))
                              (transient [c]))
                       (recur (inc i)
                              segments
                              (conj! current-segment c)))
                     (->> (persistent! current-segment)
                          (conj! segments)
                          persistent!
                          (map join))))]
    (join (interleave segments (concat html-per-column (repeat ""))))))

(defn lines->html :- [Str]
  "Returns the lines with html added."
  [lines :- [Str]]
  (let [reader (indexing-push-back-reader (join \newline lines))
        tags (sequence (comp (take-while some?) (mapcat tag-list))
                       (repeatedly (partial read-safe reader)))
        get-line #(or (:line %) (:end-line %))
        tags-by-line (group-by get-line tags)]
    (sequence (comp (partition-all 2)
                    (map (fn [[i line]]
                           (line->html line (get tags-by-line i)))))
              (interleave (iterate inc 1) lines))))

(defn results->html :- Str
  "Returns HTML for the given eval results."
  [elems :- [js/Object]
   results :- [Any]
   top-offset :- Int]
  (loop [i 0
         offset 0
         evals (transient [])]
    (if-let [elem (get elems i)]
      (let [top (-> elem .getBoundingClientRect .-top (- top-offset) (+ (.-scrollY js/window)))
            height (-> elem .getBoundingClientRect .-bottom (- top-offset) (+ (.-scrollY js/window)) (- top))
            res (get results i)]
        (recur (inc i)
               (+ offset height)
               (conj! evals
                      (str "<div class='result"
                           (when (array? res)
                             " error")
                           "' style='top: "
                           (- top offset)
                           "px; height: "
                           height
                           "px;'>"
                           (escape-html (if (array? res) (first res) res))
                           "</div>"))))
      (join (persistent! evals)))))

(defn get-collections :- [js/Object]
  "Returns collections from the given DOM node."
  [content :- js/Object]
  (vec (for [elem (-> content .-children array-seq)
             :let [classes (.-classList elem)]
             :when (or (.contains classes "collection")
                       (.contains classes "symbol"))]
         elem)))

(def ^:const rainbow-count 10)

(defn rainbow-delimiters :- {js/Object Str}
  "Returns a map of elements and class names."
  [parent :- js/Object
   level :- Int]
  (apply merge
         {}
         (for [elem (-> parent .-children array-seq)]
           (cond
             (-> elem .-classList (.contains "delimiter"))
             {elem (str "rainbow-" (mod level rainbow-count))}
             (-> elem .-classList (.contains "collection"))
             (apply merge {} (rainbow-delimiters elem (inc level)))
             :else
             {}))))

(defn line-numbers :- Str
  "Adds line numbers to the numbers."
  [line-count :- Int]
  (join (for [i (range line-count)]
          (str "<div>" (inc i) "</div>"))))

(defn custom-split-lines :- [Str]
  "Splits the string into lines."
  [s :- Str]
  (let [s (if-not (= \newline (last s))
            (str s "\n ")
            (str s " "))
        lines (split-lines s)
        last-line (last lines)
        last-line-len (max 0 (dec (count last-line)))]
    (conj (vec (butlast lines))
          (subs last-line 0 last-line-len))))

(defn move-caret!
  "Moves the caret to the specified position."
  [char-range :- js/Object
   pos :- Int]
  (set! (.-start char-range) pos)
  (set! (.-end char-range) pos))

(defn indent-caret!
  "Moves the caret to the last non-space character of the line."
  [content :- js/Object
   char-range :- js/Object]
  (let [text (.-textContent content)
        caret-position (.-start char-range)
        next-position (loop [i caret-position]
                        (if (= " " (get text i))
                          (recur (inc i))
                          i))]
    (move-caret! char-range next-position)))

(defn refresh-content!
  "Refreshes the contents."
  [content :- js/Object
   events-chan :- Any
   lines :- [Str]]
  (set! (.-innerHTML content) (join \newline (lines->html lines)))
  (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
    (events/listen elem "mouseenter" #(put! events-chan %))
    (events/listen elem "mouseleave" #(put! events-chan %)))
  (doseq [[elem class-name] (rainbow-delimiters content -1)]
    (.add (.-classList elem) class-name)))

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers :- (maybe js/Object)
   line-count :- Int]
  (when numbers
    (set! (.-innerHTML numbers) (line-numbers line-count))))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object]
  (when instarepl
    (let [elems (get-collections content)
          forms (into-array (map #(-> % .-textContent (replace \u00a0 " ")) elems))]
      (set! (.-onmessage eval-worker)
            (fn [e]
              (let [results (.-data e)
                    top-offset (-> instarepl .getBoundingClientRect .-top (+ (.-scrollY js/window)))]
                (when (.-parentNode (first elems))
                  (set! (.-innerHTML instarepl)
                        (results->html elems results top-offset))))))
      (.postMessage eval-worker forms))))

(defn pos->row-col :- [Int]
  [content :- Str
   position :- Int]
  (let [s (subs content 0 position)
        last-newline (.lastIndexOf s \newline)
        col (- position last-newline)
        row (count (re-seq #"\n" s))]
    [row (dec col)]))

(defn row-col->pos :- Int
  [content :- Str
   row :- Int
   col :- Int]
  (let [s (join \newline (take row (split content #"\n")))
        pos (+ (count s) (inc col))]
    pos))

(defn refresh!
  "Refreshes everything."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object
   & [char-code char-range]]
  (let [html (.-innerHTML content)]
    (set! (.-innerHTML content)
          (if (>= (.indexOf html "<br>") 0)
            (-> html (replace "<br>" \newline) (replace "</br>" ""))
            (-> html (replace "<div>" \newline) (replace "</div>" "")))))
  (let [text (.-textContent content)
        [row col] (if char-range
                    (pos->row-col text (.-start char-range))
                    [0 0])
        opts (when char-range
               #js {:cursorLine row
                    :cursorX col})
        result (if (= char-code 13)
                 (.parenMode js/parinfer text opts)
                 (.indentMode js/parinfer text opts))
        lines (custom-split-lines (.-text result))]
    (refresh-content! content events-chan lines)
    (refresh-numbers! numbers (dec (count lines)))
    (refresh-instarepl! instarepl content events-chan eval-worker)
    (when char-range
      (move-caret! char-range (row-col->pos (.-textContent content) row col)))
    (when (= char-code 13)
      (indent-caret! content char-range))))

(defn init! []
  (.init js/rangy)
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector paren-soup ".instarepl")
          numbers (.querySelector paren-soup ".numbers")
          content (.querySelector paren-soup ".content")
          events-chan (chan)
          eval-worker (when instarepl (js/Worker. "paren-soup-compiler.js"))]
      (set! (.-spellcheck paren-soup) false)
      (when-not content
        (throw (js/Error. "Can't find a div with class 'content'")))
      (set! (.-textContent content)
            (.-text (.parenMode js/parinfer (.-textContent content))))
      (refresh! instarepl numbers content events-chan eval-worker)
      (events/removeAll content)
      (events/listen content "keydown" #(put! events-chan %))
      (events/listen content "paste" #(put! events-chan %))
      (go (while true
            (let [event (<! events-chan)]
              (case (.-type event)
                "keydown"
                (let [char-code (.-keyCode event)]
                  (when-not (contains? #{37 38 39 40} char-code)
                    (let [sel (.getSelection js/rangy)
                          ranges (.saveCharacterRanges sel content)
                          char-range (some-> ranges (get 0) .-characterRange)]
                      (refresh! instarepl numbers content events-chan eval-worker char-code char-range)
                      (.restoreCharacterRanges sel content ranges))))
                "paste"
                (let [sel (.getSelection js/rangy)
                      ranges (.saveCharacterRanges sel content)]
                  (refresh! instarepl numbers content events-chan eval-worker)
                  (.restoreCharacterRanges sel content ranges))
                "mouseenter"
                (let [elem (.-target event)
                      x (.-clientX event)
                      y (.-clientY event)]
                  (let [popup (.createElement js/document "div")]
                    (aset popup "textContent" (-> elem .-dataset .-message))
                    (aset (.-style popup) "top" (str y "px"))
                    (aset (.-style popup) "left" (str x "px"))
                    (aset popup "className" "error-text")
                    (.appendChild paren-soup popup)))
                "mouseleave"
                (doseq [elem (-> paren-soup (.querySelectorAll ".error-text") array-seq)]
                  (.removeChild paren-soup elem))
                nil)))))))

(defn init-debug! []
  (.log js/console (with-out-str (time (with-fn-validation (init!))))))

(set! (.-onload js/window) init!)

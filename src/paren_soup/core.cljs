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

(defn show-error!
  "Shows a popup with an error message."
  [parent-elem :- js/Object
   event :- js/Object]
  (let [elem (.-target event)
        x (.-clientX event)
        y (.-clientY event)]
    (let [popup (.createElement js/document "div")]
      (aset popup "textContent" (-> elem .-dataset .-message))
      (aset (.-style popup) "top" (str y "px"))
      (aset (.-style popup) "left" (str x "px"))
      (aset popup "className" "error-text")
      (.appendChild parent-elem popup))))

(defn hide-errors!
  "Hides all error popups."
  [parent-elem :- js/Object]
  (doseq [elem (-> parent-elem (.querySelectorAll ".error-text") array-seq)]
    (.removeChild parent-elem elem)))

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

(defn get-selection :- js/Object
  "Returns the objects related to selection for the given element."
  [content :- js/Object]
  (let [selection (.getSelection js/rangy)
        ranges (.saveCharacterRanges selection content)
        char-range (some-> ranges (get 0) .-characterRange)]
    {:selection selection :ranges ranges :char-range char-range}))

(defn get-cursor-index :- Int
  "Returns the index of the cursor position."
  [content :- js/Object]
  (or (some-> content get-selection :char-range .-start) -1))

(defn move-cursor!
  "Moves the cursor to the specified position."
  [content :- js/Object
   pos :- Int]
  (let [{:keys [selection ranges char-range]} (get-selection content)]
    (when (and selection ranges char-range)
      (set! (.-start char-range) pos)
      (set! (.-end char-range) pos)
      (.restoreCharacterRanges selection content ranges))))

(defn refresh-content!
  "Refreshes the contents."
  [content :- js/Object
   events-chan :- Any
   state :- {Keyword Any}]
  (set! (.-innerHTML content) (join \newline (lines->html (:lines state))))
  (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
    (events/listen elem "mouseenter" #(put! events-chan %))
    (events/listen elem "mouseleave" #(put! events-chan %)))
  (doseq [[elem class-name] (rainbow-delimiters content -1)]
    (.add (.-classList elem) class-name))
  (move-cursor! content (:index state)))

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

(defn index->row-col :- [Int]
  "Converts an index to a row and column number."
  [text :- Str
   index :- Int]
  (let [s (subs text 0 index)
        last-newline (.lastIndexOf s \newline)
        col (- index last-newline)
        row (count (re-seq #"\n" s))]
    [row (dec col)]))

(defn row-col->index :- Int
  "Converts a row and column number to an index."
  [text :- Str
   row :- Int
   col :- Int]
  (let [s (join \newline (take row (split text #"\n")))
        index (+ (count s) (inc col))]
    index))

(defn br->newline!
  "Replaces <br> tags with newline chars."
  [content :- js/Object]
  (let [html (.-innerHTML content)]
    (set! (.-innerHTML content)
          (if (>= (.indexOf html "<br>") 0)
            (-> html (replace "<br>" \newline) (replace "</br>" ""))
            (-> html (replace "<div>" \newline) (replace "</div>" ""))))))

(defn update-edit-history!
  "Updates the edit history atom."
  [edit-history :- Any
   state :- {Keyword Any}]
  (swap! edit-history update-in [:current-state] inc)
  (swap! edit-history update-in [:states] subvec 0 (:current-state @edit-history))
  (swap! edit-history update-in [:states] conj state))

(defn get-parinfer-opts :- js/Object
  "Returns an options object for parinfer."
  [text :- Str
   index :- Int]
  (let [[row col] (index->row-col text index)]
    #js {:cursorLine row :cursorX col}))

(defn get-state! :- Any
  "Returns the updated state of the text editor."
  [content :- js/Object
   paren-mode? :- Bool]
  (let [index (get-cursor-index content)
        _ (br->newline! content)
        text (.-textContent content)
        opts (get-parinfer-opts text index)
        result (if paren-mode?
                 (.parenMode js/parinfer text opts)
                 (.indentMode js/parinfer text opts))
        text (.-text result)
        lines (custom-split-lines text)
        index (row-col->index text (.-cursorLine opts) (.-cursorX result))]
    {:lines lines :index index}))

(defn refresh!
  "Refreshes everything."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object
   edit-history :- Any
   paren-mode? :- Bool]
  (let [state (get-state! content paren-mode?)]
    (update-edit-history! edit-history state)
    (refresh-content! content events-chan state)
    (refresh-numbers! numbers (dec (count (:lines state))))
    (refresh-instarepl! instarepl content events-chan eval-worker)))

(defn init! []
  (.init js/rangy)
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector paren-soup ".instarepl")
          numbers (.querySelector paren-soup ".numbers")
          content (.querySelector paren-soup ".content")
          events-chan (chan)
          eval-worker (when instarepl (js/Worker. "paren-soup-compiler.js"))
          edit-history (atom {:current-state -1 :states []})]
      (set! (.-spellcheck paren-soup) false)
      (when-not content
        (throw (js/Error. "Can't find a div with class 'content'")))
      (refresh! instarepl numbers content events-chan eval-worker edit-history true)
      (events/removeAll content)
      (events/listen content "keydown" (fn [e]
                                         (put! events-chan e)
                                         (when (and (.-metaKey e) (= (.-keyCode e) 90))
                                           (.preventDefault e))))
      (events/listen content "paste" #(put! events-chan %))
      (go (while true
            (let [event (<! events-chan)]
              (case (.-type event)
                "keydown"
                (let [char-code (.-keyCode event)]
                  (cond
                    (and (.-metaKey event) (= char-code 90))
                    (let [{:keys [current-state states]} @edit-history]
                      (if (.-shiftKey event)
                        (when-let [state (get states (inc current-state))]
                          (swap! edit-history update-in [:current-state] inc)
                          (refresh-content! content events-chan state)
                          (refresh-numbers! numbers (dec (count (:lines state))))
                          (refresh-instarepl! instarepl content events-chan eval-worker))
                        (when-let [state (get states (dec current-state))]
                          (swap! edit-history update-in [:current-state] dec)
                          (refresh-content! content events-chan state)
                          (refresh-numbers! numbers (dec (count (:lines state))))
                          (refresh-instarepl! instarepl content events-chan eval-worker))))
                    
                    (not (contains? #{37 38 39 40 ; arrows
                                      16 ; shift
                                      17 ; ctrl
                                      18 ; alt
                                      91 93 ; meta
                                      }
                                    char-code))
                    (refresh! instarepl numbers content events-chan eval-worker edit-history (= char-code 13))))
                "paste"
                (refresh! instarepl numbers content events-chan eval-worker edit-history false)
                "mouseenter"
                (show-error! paren-soup event)
                "mouseleave"
                (hide-errors! paren-soup)
                nil)))))))

(defn init-debug! []
  (.log js/console (with-out-str (time (with-fn-validation (init!))))))

(set! (.-onload js/window) init!)

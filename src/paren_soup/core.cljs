(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [cljs.tools.reader :refer [read *wrap-value-and-add-metadata?*]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.data :refer [diff]]
            [clojure.string :refer [escape join replace]]
            [goog.events :as events]
            [rangy.core]
            [rangy.textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [parinfer.core]
            [mistakes-were-made.core :as mwm])
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
   (tag-list token 0))
  ([token :- Any
    parent-indent :- Int]
   (flatten
     (cond
       ; an error
       (instance? js/Error token)
       [(assoc (.-data token) :message (.-message token) :error? true)]
       
       ; a key-value pair from a map
       (and (coll? token) (nil? (meta token)))
       (map tag-list token)
       
       ; a valid token
       :else
       (let [{:keys [line column end-line end-column wrapped?]} (meta token)
             value (if wrapped? (first token) token)]
         [; begin tag
          {:line line :column column :value value}
          (if (coll? value)
            (let [delimiter-size (if (set? value) 2 1)
                  new-end-column (+ column delimiter-size)]
              [; open delimiter tags
               {:line line :column column :delimiter? true}
               {:end-line line :end-column new-end-column :next-line-indent new-end-column}
                ; child tags
               (map #(tag-list % new-end-column) value)
                ; close delimiter tags
               {:line end-line :column (dec end-column) :delimiter? true}
               {:end-line end-line :end-column end-column :next-line-indent parent-indent}])
            [])
           ; end tag
          {:end-line end-line :end-column end-column}])))))

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
    (:indent? tag) (str "<span class='indent'>"
                        (join (repeat (:count tag) " "))
                        "</span>")
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

(defn text->tags :- [{Keyword Any}]
  "Returns the tags for the given string containing code."
  [text :- Str]
  (let [reader (indexing-push-back-reader text)]
    (sequence (comp (take-while some?) (mapcat tag-list))
              (repeatedly (partial read-safe reader)))))

(defn lines->html :- [Str]
  "Returns the lines with html added."
  [lines :- [Str]
   tags :- [{Keyword Any}]]
  (let [tags-by-line (group-by #(or (:line %) (:end-line %)) tags)]
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

(defn get-selection :- js/Object
  "Returns the objects related to selection for the given element."
  [content :- js/Object]
  (let [selection (.getSelection js/rangy)
        ranges (.saveCharacterRanges selection content)
        char-range (some-> ranges (get 0) .-characterRange)]
    {:selection selection :ranges ranges :char-range char-range}))

(defn get-cursor-position :- Int
  "Returns the cursor position."
  [content :- js/Object]
  (or (some-> content get-selection :char-range .-start) 0))

(defn set-cursor-position!
  "Moves the cursor to the specified position."
  [content :- js/Object
   pos :- Int]
  (let [{:keys [selection ranges char-range]} (get-selection content)]
    (when (and selection ranges char-range)
      (set! (.-start char-range) pos)
      (set! (.-end char-range) pos)
      (.restoreCharacterRanges selection content ranges))))

(defn refresh-content! :- {Keyword Any}
  "Refreshes the contents."
  [content :- js/Object
   events-chan :- Any
   state :- {Keyword Any}]
  (let [lines (if-not (empty? (last (:lines state))) ; add a new line at the end if necessary
                (conj (vec (:lines state)) "")
                (:lines state))
        text (join \newline lines)
        tags (text->tags text)
        html-text (join \newline (lines->html lines tags))]
    (set! (.-innerHTML content) html-text)
    (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
      (events/listen elem "mouseenter" #(put! events-chan %))
      (events/listen elem "mouseleave" #(put! events-chan %)))
    (doseq [[elem class-name] (rainbow-delimiters content -1)]
      (.add (.-classList elem) class-name))
    (set-cursor-position! content (:cursor-position state))
    state))

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

(defn br->newline!
  "Replaces <br> tags with newline chars."
  [content :- js/Object]
  (let [html (.-innerHTML content)]
    (set! (.-innerHTML content)
          (if (>= (.indexOf html "<br>") 0)
            (-> html (replace "<br>" \newline) (replace "</br>" ""))
            (-> html (replace "<div>" \newline) (replace "</div>" ""))))))

(defn get-parinfer-opts :- js/Object
  "Returns an options object for parinfer."
  [text :- Str
   cursor-position :- Int]
  (let [[row col] (mwm/position->row-col text cursor-position)]
    #js {:cursorLine row :cursorX col}))

(defn get-state! :- Any
  "Returns the updated state of the text editor."
  [content :- js/Object
   paren-mode? :- Bool]
  (let [old-position (get-cursor-position content)
        _ (br->newline! content)
        old-text (.-textContent content)
        opts (get-parinfer-opts old-text old-position)
        result (if paren-mode?
                 (.parenMode js/parinfer old-text opts)
                 (.indentMode js/parinfer old-text opts))]
    (mwm/get-state (.-text result) (.-cursorLine opts) (.-cursorX result))))

(defn refresh! :- {Keyword Any}
  "Refreshes everything."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object
   state :- {Keyword Any}]
  (let [state (refresh-content! content events-chan state)]
    (refresh-numbers! numbers (dec (count (:lines state))))
    (refresh-instarepl! instarepl content events-chan eval-worker)
    state))

(defn undo-or-redo? [e]
  (and (or (.-metaKey e) (.-ctrlKey e))
       (= (.-keyCode e) 90)))

(defn init! []
  (.init js/rangy)
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector paren-soup ".instarepl")
          numbers (.querySelector paren-soup ".numbers")
          content (.querySelector paren-soup ".content")
          events-chan (chan)
          eval-worker (when instarepl (js/Worker. "paren-soup-compiler.js"))
          edit-history (mwm/create-edit-history)]
      (set! (.-spellcheck paren-soup) false)
      (when-not content
        (throw (js/Error. "Can't find a div with class 'content'")))
      (->> (assoc (get-state! content true) :cursor-position 0)
           (refresh! instarepl numbers content events-chan eval-worker)
           (mwm/update-edit-history! edit-history))
      (events/removeAll content)
      (events/listen content "keydown" (fn [e]
                                         (put! events-chan e)
                                         (when (undo-or-redo? e)
                                           (.preventDefault e))))
      (events/listen content "keyup" #(put! events-chan %))
      (events/listen content "mouseup" #(put! events-chan %))
      (events/listen content "paste" #(put! events-chan %))
      (go (while true
            (let [event (<! events-chan)]
              (case (.-type event)
                "keydown"
                (when (undo-or-redo? event)
                  (if (.-shiftKey event)
                    (when-let [state (mwm/redo! edit-history)]
                      (refresh! instarepl numbers content events-chan eval-worker state))
                    (when-let [state (mwm/undo! edit-history)]
                      (refresh! instarepl numbers content events-chan eval-worker state))))
                "keyup"
                (cond
                  (contains? #{37 38 39 40} (.-keyCode event))
                  (mwm/update-cursor-position! edit-history (get-cursor-position content))
                  
                  (not (or (contains? #{16 ; shift
                                        17 ; ctrl
                                        18 ; alt
                                        91 93} ; meta
                                      (.-keyCode event))
                           (.-ctrlKey event)
                           (.-metaKey event)))
                  (->> (get-state! content (= (.-keyCode event) 13))
                       (refresh! instarepl numbers content events-chan eval-worker)
                       (mwm/update-edit-history! edit-history)))
                "paste"
                (->> (get-state! content false)
                     (refresh! instarepl numbers content events-chan eval-worker)
                     (mwm/update-edit-history! edit-history))
                "mouseup"
                (mwm/update-cursor-position! edit-history (get-cursor-position content))
                "mouseenter"
                (show-error! paren-soup event)
                "mouseleave"
                (hide-errors! paren-soup)
                nil)))))))

(defn init-debug! []
  (.log js/console (with-out-str (time (with-fn-validation (init!))))))

(set! (.-onload js/window) init!)

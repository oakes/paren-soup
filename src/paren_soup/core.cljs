(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.data :refer [diff]]
            [clojure.string :refer [escape join replace triml]]
            [goog.events :as events]
            [rangy.core]
            [rangy.textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [parinfer.core]
            [mistakes-were-made.core :as mwm]
            [tag-soup.core :as ts])
  (:require-macros [schema.core :refer [defn with-fn-validation]]
                   [cljs.core.async.macros :refer [go]]))

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

(defn get-cursor-position :- [Int]
  "Returns the cursor position."
  [content :- js/Object]
  (if-let [range (some-> content get-selection :char-range)]
    [(.-start range) (.-end range)]
    [0 0]))

(defn set-cursor-position!
  "Moves the cursor to the specified position."
  [content :- js/Object
   &
   [start-pos :- Int
    end-pos :- Int]]
  (let [{:keys [selection ranges char-range]} (get-selection content)]
    (when (and selection ranges char-range)
      (set! (.-start char-range) start-pos)
      (set! (.-end char-range) (or end-pos start-pos))
      (.restoreCharacterRanges selection content ranges))))

(defn add-indent!
  "Adds indent to the line with the cursor."
  [content :- js/Object
   lines :- [Str]
   text :- Str
   tags :- [{Keyword Any}]
   state :- {Keyword Any}]
  (let [start-pos (first (:cursor-position state))
        [cursor-line _] (mwm/position->row-col text start-pos)
        indent-level (case (:indent-type state)
                       :return
                       (ts/indent-for-line tags cursor-line)
                       :back
                       (ts/change-indent-for-line tags cursor-line true)
                       :forward
                       (ts/change-indent-for-line tags cursor-line false))
        lines (update lines
                cursor-line
                (fn [line]
                  (str (join (repeat indent-level " ")) (triml line))))
        text (join \newline lines)]
    (set! (.-innerHTML content) text)
    (set-cursor-position! content (+ start-pos indent-level))))

(defn refresh-content! :- {Keyword Any}
  "Refreshes the contents."
  [content :- js/Object
   events-chan :- Any
   state :- {Keyword Any}]
  (let [lines (if-not (empty? (last (:lines state))) ; add a new line at the end if necessary
                (conj (:lines state) "")
                (:lines state))
        [start-pos end-pos] (:cursor-position state)
        text (join \newline lines)
        tags (ts/str->tags text)
        html-lines (lines->html lines tags)]
    ; add the new html, indent if necessary, and reset the cursor position
    (if (:indent-type state)
      (add-indent! content (vec html-lines) text tags state)
      (let [html-text (join \newline html-lines)]
        (set! (.-innerHTML content) html-text)
        (set-cursor-position! content start-pos end-pos)))
    ; set the mouseover events for errors
    (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
      (events/listen elem "mouseenter" #(put! events-chan %))
      (events/listen elem "mouseleave" #(put! events-chan %)))
    ; add rainbow delimiters
    (doseq [[elem class-name] (rainbow-delimiters content -1)]
      (.add (.-classList elem) class-name))
    ; return the state
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
                (when (some-> elems first .-parentNode)
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

(defn init-state! :- {Keyword Any}
  "Returns the editor's state after sanitizing it."
  [content :- js/Object]
  (let [pos (get-cursor-position content)
        _ (br->newline! content)
        text (.-textContent content)]
    {:cursor-position pos
     :text text}))

(defn get-parinfer-state :- {Keyword Any}
  "Returns the updated state of the text editor using parinfer."
  [paren-mode? :- Bool
   initial-state :- {Keyword Any}]
  (let [{:keys [cursor-position text]} initial-state
        [start-pos end-pos] cursor-position
        selected? (not= start-pos end-pos)
        opts (when-not selected?
               (get-parinfer-opts text start-pos))
        result (if paren-mode?
                 (.parenMode js/parinfer text opts)
                 (.indentMode js/parinfer text opts))]
    (if selected?
      (mwm/get-state (.-text result) cursor-position)
      (mwm/get-state (.-text result) (.-cursorLine opts) (.-cursorX result)))))

(defn get-normal-state :- {Keyword Any}
  "Returns the updated state of the text editor."
  [initial-state :- {Keyword Any}]
  (let [{:keys [cursor-position text]} initial-state]
    (assoc (mwm/get-state text cursor-position) :indent-type :return)))

(defn indent-line :- {Keyword Any}
  "Indents the given line."
  [lines :- [Str]
   line-to-change :- Int]
  (update lines line-to-change #(str "  " %)))

(defn unindent-line :- {Keyword Any}
  "Unindents the given line."
  [lines :- [Str]
   line-to-change :- Int]
  (update lines line-to-change
    (fn [line]
      (str (->> line (take 2) (drop-while #(= % \space)) join)
           (subs line 2)))))

(defn get-indent-state :- {Keyword Any}
  "Returns the state with indentation applied."
  [reverse? :- Bool
   initial-state :- {Keyword Any}]
  (let [{:keys [cursor-position text]} initial-state]
    (assoc (mwm/get-state text cursor-position)
      :indent-type (if reverse? :back :forward)))
  #_
  (let [; add indentation to the text
        {:keys [cursor-position text]} initial-state
        [start-pos end-pos] cursor-position
        [start-line start-x] (mwm/position->row-col text start-pos)
        [end-line end-x] (mwm/position->row-col text end-pos)
        lines-to-change (range start-line (inc end-line))
        lines (mwm/split-lines text)
        lines (if reverse?
                (reduce unindent-line lines lines-to-change)
                (reduce indent-line lines lines-to-change))
        initial-state (assoc initial-state :text (join \newline lines))
        ; run parinfer on the text
        state (get-parinfer-state false initial-state)
        ; adjust the cursor position
        lines (:lines state)
        new-text (join \newline lines)
        selected? (not= start-pos end-pos)
        indent-change (if reverse? -2 2)
        start-x (if selected?
                  0
                  (max 0 (+ indent-change start-x)))
        end-x (if selected?
                (count (get lines end-line))
                (max 0 (+ indent-change end-x)))
        new-start-pos (mwm/row-col->position new-text start-line start-x)
        new-end-pos (mwm/row-col->position new-text end-line end-x)]
    (assoc state :cursor-position [new-start-pos new-end-pos])))

(defn refresh! :- {Keyword Any}
  "Refreshes everything."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object
   state :- {Keyword Any}]
  (refresh-content! content events-chan state)
  (refresh-numbers! numbers (dec (count (:lines state))))
  (refresh-instarepl! instarepl content events-chan eval-worker)
  state)

(defn undo-or-redo? [e]
  (and (or (.-metaKey e) (.-ctrlKey e))
       (= (.-keyCode e) 90)))

(defn tab? [e]
  (= (.-keyCode e) 9))

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
      (->> (init-state! content)
           (get-parinfer-state true)
           (refresh! instarepl numbers content events-chan eval-worker)
           (mwm/update-edit-history! edit-history))
      (events/removeAll content)
      (events/listen content "keydown" (fn [e]
                                         (put! events-chan e)
                                         (when (or (undo-or-redo? e) (tab? e))
                                           (.preventDefault e))))
      (events/listen content "keyup" #(put! events-chan %))
      (events/listen content "mouseup" #(put! events-chan %))
      (events/listen content "cut" #(put! events-chan %))
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
                  (let [initial-state (init-state! content)]
                    (->> (case (.-keyCode event)
                           13 (get-normal-state initial-state)
                           9 (get-indent-state (.-shiftKey event) initial-state)
                           (get-parinfer-state false initial-state))
                         (refresh! instarepl numbers content events-chan eval-worker)
                         (mwm/update-edit-history! edit-history))))
                "cut"
                (->> (init-state! content)
                     (get-parinfer-state false)
                     (refresh! instarepl numbers content events-chan eval-worker)
                     (mwm/update-edit-history! edit-history))
                "paste"
                (->> (init-state! content)
                     (get-parinfer-state false)
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

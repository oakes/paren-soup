(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.string :refer [join replace]]
            [goog.events :as events]
            [goog.string :refer [format]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [mistakes-were-made.core :as mwm]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp])
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

(defn elems->locations :- [{Keyword Any}]
  "Returns the location of each elem."
  [elems :- [js/Object]
   top-offset :- Int]
  (loop [i 0
         locations (transient [])]
    (if-let [elem (get elems i)]
      (let [top (-> elem .-offsetTop (- top-offset))
            height (-> elem .-offsetHeight)]
        (recur (inc i) (conj! locations {:top top :height height})))
      (persistent! locations))))

(defn results->html :- Str
  "Returns HTML for the given eval results."
  [results :- [Any]
   locations :- [{Keyword Any}]]
  (loop [i 0
         evals (transient [])]
    (let [res (get results i)
          {:keys [top height]} (get locations i)]
      (if (and res top height)
        (recur (inc i)
               (conj! evals
                 (format
                   "<div class='%s' style='top: %spx; height: %spx; min-height: %spx'>%s</div>"
                   (if (array? res) "result error" "result")
                   top
                   height
                   height
                   (some-> (if (array? res) (first res) res)
                           hs/escape-html-str))))
        (join (persistent! evals))))))

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
        char-range (some-> ranges (aget 0) (aget "characterRange"))]
    {:selection selection :ranges ranges :char-range char-range}))

(defn get-cursor-position :- [Int]
  "Returns the cursor position."
  [content :- js/Object]
  (if-let [range (some-> content get-selection :char-range)]
    [(aget range "start") (aget range "end")]
    [0 0]))

(defn set-cursor-position!
  "Moves the cursor to the specified position."
  [content :- js/Object
   &
   [start-pos :- Int
    end-pos :- Int]]
  (let [{:keys [selection ranges char-range]} (get-selection content)]
    (when (and selection ranges char-range)
      (aset char-range "start" start-pos)
      (aset char-range "end" (or end-pos start-pos))
      (.restoreCharacterRanges selection content ranges))))

(defn update-editor!
  "Adds error messages and rainbow delimiters to the editor."
  [content :- js/Object
   events-chan :- js/Object]
  ; set the mouseover events for errors
  (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
    (events/listen elem "mouseenter" #(put! events-chan %))
    (events/listen elem "mouseleave" #(put! events-chan %)))
  ; add rainbow delimiters
  (doseq [[elem class-name] (rainbow-delimiters content -1)]
    (.add (.-classList elem) class-name)))

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers :- js/Object
   line-count :- Int]
  (set! (.-innerHTML numbers) (line-numbers line-count)))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl :- js/Object
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object]
  (let [elems (get-collections content)
        locations (elems->locations elems (.-offsetTop instarepl))
        forms (into-array (map #(-> % .-textContent (replace \u00a0 " ")) elems))]
    (set! (.-onmessage eval-worker)
          (fn [e]
            (let [results (.-data e)]
              (when (some-> elems first .-parentNode)
                (set! (.-innerHTML instarepl)
                      (results->html results locations))))))
    (.postMessage eval-worker forms)))

(defn post-refresh!
  "Refreshes things after the content has been refreshed."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object
   state :- {Keyword Any}]
  (let [[start-pos end-pos] (:cursor-position state)]
    (set-cursor-position! content start-pos end-pos)
    (update-editor! content events-chan)
    (some-> numbers (refresh-numbers! (count (re-seq #"\n" (:text state)))))
    (some-> instarepl (refresh-instarepl! content events-chan eval-worker))))

(defn refresh!
  "Refreshes everything."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any
   eval-worker :- js/Object
   state-atom :- Any]
  (set! (.-innerHTML content) (hs/code->html (:text @state-atom)))
  (post-refresh! instarepl numbers content events-chan eval-worker @state-atom))

(defn adjust-state :- {Keyword Any}
  "Adds a newline and indentation to the state if necessary."
  [state :- {Keyword Any}]
  (let [state (if-not (= \newline (last (:text state)))
                (assoc state :text (str (:text state) \newline))
                state)
        state (if (:indent-type state)
                (cp/add-indent state)
                state)]
    state))

(defn init-state :- {Keyword Any}
  "Returns the editor's state after sanitizing it."
  [content :- js/Object]
  (let [pos (get-cursor-position content)
        text (.-textContent content)]
    {:cursor-position pos
     :text text}))

(defn key-name? :- Bool
  "Returns true if the supplied key event involves the key(s) described by key-name."
  [event :- js/Object
   key-name :- Keyword]
  (case key-name
    :undo-or-redo
    (and (or (.-metaKey event) (.-ctrlKey event))
       (= (.-keyCode event) 90))
    :tab
    (= (.-keyCode event) 9)
    :enter
    (= (.-keyCode event) 13)
    :arrows
    (contains? #{37 38 39 40} (.-keyCode event))
    :general
    (not (or (contains? #{16 ; shift
                          17 ; ctrl
                          18 ; alt
                          91 93} ; meta
                         (.-keyCode event))
             (.-ctrlKey event)
             (.-metaKey event)))
    false))

(defn init! []
  (.init js/rangy)
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector paren-soup ".instarepl")
          numbers (.querySelector paren-soup ".numbers")
          content (.querySelector paren-soup ".content")
          events-chan (chan)
          eval-worker (when instarepl (js/Worker. "paren-soup-compiler.js"))
          edit-history (mwm/create-edit-history)
          current-state (atom nil)]
      (set! (.-spellcheck paren-soup) false)
      (when-not content
        (throw (js/Error. "Can't find a div with class 'content'")))
      (->> (init-state content)
           (cp/add-parinfer :paren)
           (adjust-state)
           (reset! current-state)
           (mwm/update-edit-history! edit-history))
      (refresh! instarepl numbers content events-chan eval-worker current-state)
      (add-watch current-state :render
        (fn [_ _ _ _]
          (refresh! instarepl numbers content events-chan eval-worker current-state)))
      (doto content
        (events/removeAll)
        (events/listen "keydown" (fn [e]
                                   (put! events-chan e)
                                   (when (or (key-name? e :undo-or-redo)
                                             (key-name? e :tab)
                                             (key-name? e :enter))
                                     (.preventDefault e))))
        (events/listen "keyup" #(put! events-chan %))
        (events/listen "mouseup" #(put! events-chan %))
        (events/listen "cut" #(put! events-chan %))
        (events/listen "paste" #(put! events-chan %)))
      (go (while true
            (let [event (<! events-chan)]
              (case (.-type event)
                "keydown"
                (cond
                  (key-name? event :undo-or-redo)
                  (if (.-shiftKey event)
                    (when-let [state (mwm/redo! edit-history)]
                      (reset! current-state (adjust-state state)))
                    (when-let [state (mwm/undo! edit-history)]
                      (reset! current-state (adjust-state state))))
                  (key-name? event :enter)
                  (.execCommand js/document "insertHTML" false "\n"))
                "keyup"
                (cond
                  (key-name? event :arrows)
                  (mwm/update-cursor-position! edit-history (get-cursor-position content))
                  (key-name? event :general)
                  (let [state (init-state content)]
                    (->> (case (.-keyCode event)
                           13 (assoc  state :indent-type :return)
                           9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                           (cp/add-parinfer :indent state))
                         (adjust-state)
                         (reset! current-state)
                         (mwm/update-edit-history! edit-history))))
                "cut"
                (->> (init-state content)
                     (cp/add-parinfer :both)
                     (adjust-state)
                     (reset! current-state)
                     (mwm/update-edit-history! edit-history))
                "paste"
                (->> (init-state content)
                     (cp/add-parinfer :both)
                     (adjust-state)
                     (reset! current-state)
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

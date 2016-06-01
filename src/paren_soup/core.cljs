(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.string :refer [join replace]]
            [goog.events :as events]
            [goog.functions :refer [debounce]]
            [goog.string :refer [format]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]]
            [mistakes-were-made.core :as mwm]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp])
  (:require-macros [schema.core :refer [defn with-fn-validation]]
                   [cljs.core.async.macros :refer [go]]))

(defn show-error-message!
  "Shows a popup with an error message."
  [parent-elem :- js/Object
   event :- js/Object]
  (let [elem (.-target event)
        x (.-clientX event)
        y (.-clientY event)
        popup (.createElement js/document "div")]
    (aset popup "textContent" (-> elem .-dataset .-message))
    (aset (.-style popup) "top" (str y "px"))
    (aset (.-style popup) "left" (str x "px"))
    (aset popup "className" "error-text")
    (.appendChild parent-elem popup)))

(def show-error-icon!
  (debounce
    (fn [elem]
      (set! (.-display (.-style elem)) "inline-block"))
    1000))

(defn hide-error-messages!
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

(defn rainbow-delimiters :- Any
  "Returns a map of elements and class names."
  ([parent :- js/Object
    level :- Int]
   (persistent! (rainbow-delimiters parent level (transient {}))))
  ([parent :- js/Object
    level :- Int
    m :- Any]
   (reduce
     (fn [m elem]
       (let [classes (.-classList elem)]
         (cond
           (.contains classes "delimiter")
           (assoc! m elem (str "rainbow-" (mod level rainbow-count)))
           (.contains classes "collection")
           (rainbow-delimiters elem (inc level) m)
           :else
           m)))
     m
     (-> parent .-children array-seq))))

(defn line-numbers :- Str
  "Adds line numbers to the numbers."
  [line-count :- Int]
  (join (for [i (range line-count)]
          (str "<div>" (inc i) "</div>"))))

(defn get-parents :- js/Object
  "Returns the parents of the given node."
  [node :- js/Object]
  (loop [node node
         nodes '()]
    (if-let [parent (.-parentElement node)]
      (if (.contains (.-classList parent) "collection")
        (recur parent (conj nodes parent))
        (recur parent nodes))
      nodes)))

(defn text-node? [node]
  (= 3 (.-nodeType node)))

(defn error-node? [node]
  (some-> node .-classList (.contains "error")))

(defn top-level? [node]
  (some-> node .-parentElement .-classList (.contains "content")))

(defn common-ancestor :- (maybe js/Object)
  "Returns the common ancestor of the given nodes."
  [first-node :- js/Object
   second-node :- js/Object]
  (let [first-parent (first (get-parents first-node))
        second-parent (first (get-parents second-node))]
    (cond
      ; a parent element
      (and first-parent second-parent (= first-parent second-parent))
      first-parent
      ; a top-level text node
      (and (= first-node second-node)
           (text-node? first-node)
           (top-level? first-node))
      first-node)))

(defn char-range->position :- [Int]
  "Returns the position from the given char range object."
  [char-range :- (maybe js/Object)]
  (if char-range
    [(aget char-range "start") (aget char-range "end")]
    [0 0]))

(defn get-selection :- {Keyword Any}
  "Returns the objects related to selection for the given element."
  [content :- js/Object]
  (let [selection (.getSelection js/rangy)
        ranges (.saveCharacterRanges selection content)
        char-range (some-> ranges (aget 0) (aget "characterRange"))
        anchor (.-anchorNode selection)
        focus (.-focusNode selection)
        parent (when (and anchor focus)
                 (common-ancestor anchor focus))]
    {:selection selection
     :ranges ranges
     :char-range char-range
     :cursor-position (char-range->position char-range)
     :cropped-selection
     (when parent
       {:element parent
        :cursor-position
        (let [ranges (.saveCharacterRanges selection parent)
              char-range (some-> ranges (aget 0) (aget "characterRange"))]
          (char-range->position char-range))})}))

(defn get-cursor-position :- [Int]
  "Returns the cursor position."
  [content :- js/Object]
  (:cursor-position (get-selection content)))

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

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers :- js/Object
   line-count :- Int]
  (set! (.-innerHTML numbers) (line-numbers line-count)))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl :- js/Object
   content :- js/Object
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

(defn post-refresh-content!
  "Does additional work on the content after it is rendered."
  [content :- js/Object
   events-chan :- Any
   state :- {Keyword Any}]
  ; set the cursor position
  (if-let [crop (:cropped-state state)]
    (apply set-cursor-position! (:element crop) (:cursor-position crop))
    (apply set-cursor-position! content (:cursor-position state)))
  ; set up errors
  (hide-error-messages! (.-parentElement content))
  (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
    (show-error-icon! elem)
    (events/listen elem "mouseenter" #(put! events-chan %))
    (events/listen elem "mouseleave" #(put! events-chan %)))
  ; add rainbow delimiters
  (doseq [[elem class-name] (rainbow-delimiters content -1)]
    (.add (.-classList elem) class-name)))

(defn refresh-content-element!
  "Replaces a single node in the content, and siblings if necessary."
  [cropped-state]
  (let [{:keys [text element]} cropped-state
        parent (.-parentElement element)
        ; find all siblings that should be refreshed as well
        siblings (loop [elems []
                        current-elem element]
                   (if (or (text-node? current-elem) (error-node? current-elem))
                     (if-let [sibling (.-nextSibling current-elem)]
                       (recur (conj elems sibling) sibling)
                       elems)
                     elems))
        ; add siblings' text to the string
        text (str text (join (map #(.-textContent %) siblings)))
        ; create temporary element
        temp-elem (.createElement js/document "span")
        _ (set! (.-innerHTML temp-elem) (hs/code->html text))
        ; collect elements
        new-elems (doall
                    (for [i (range (-> temp-elem .-childNodes .-length))]
                      (-> temp-elem .-childNodes (.item i))))
        old-elems (cons element siblings)]
    ; insert the new nodes
    (doseq [new-elem new-elems]
      (.insertBefore parent new-elem element))
    ; remove the old nodes
    (doseq [old-elem old-elems]
      (.removeChild parent old-elem))
    (assoc cropped-state :element (first new-elems))))

(defn refresh-content!
  "Refreshes the content."
  [content :- js/Object
   state :- {Keyword Any}]
  (if-let [crop (:cropped-state state)]
    (let [crop (refresh-content-element! crop)]
      ; if there were changes outside the node, we need to run it on the whole document instead
      (if (not= (:text state) (.-textContent content))
        (refresh-content! content (dissoc state :cropped-state))
        (assoc state :cropped-state crop)))
    (do
      (set! (.-innerHTML content) (hs/code->html (:text state)))
      state)))

(defn add-parinfer :- {Keyword Any}
  "Adds parinfer to the state."
  [mode-type :- Keyword
   state :- {Keyword Any}]
  (let [state (cp/add-parinfer mode-type state)]
    (if-let [crop (:cropped-state state)]
      (assoc state :cropped-state
        (merge crop (cp/add-parinfer mode-type crop)))
      state)))

(defn adjust-state :- {Keyword Any}
  "Adds a newline and indentation to the state if necessary."
  [state :- {Keyword Any}]
  (let [{:keys [text indent-type cropped-state]} state
        ; add newline at end if necessary
        state (if-not (= \newline (last text))
                (assoc state :text (str text \newline))
                state)
        ; fix indentation of the state
        state (if indent-type
                (cp/add-indent state)
                state)
        ; fix indentation of the cropped state
        state (if (and indent-type cropped-state)
                (assoc state :cropped-state
                  (merge cropped-state
                    (cp/add-indent (assoc cropped-state :indent-type indent-type))))
                state)]
    state))

(defn init-state :- {Keyword Any}
  "Returns the editor's state after sanitizing it."
  [content :- js/Object
   crop? :- Bool]
  (let [sel (get-selection content)
        pos (:cursor-position sel)
        text (.-textContent content)
        state {:cursor-position pos :text text}]
    (if-let [cropped-selection (:cropped-selection sel)]
      (if crop?
        (assoc state :cropped-state
          (assoc cropped-selection
            :text (.-textContent (:element cropped-selection))))
        state)
      state)))

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

(defn full-refresh!
  "Refreshes the content completely."
  [content current-state edit-history]
  (->> (init-state content false)
       (add-parinfer :both)
       (adjust-state)
       (reset! current-state)
       (mwm/update-edit-history! edit-history)))

(defn init! []
  (.init js/rangy)
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector paren-soup ".instarepl")
          numbers (.querySelector paren-soup ".numbers")
          content (.querySelector paren-soup ".content")
          eval-worker (when instarepl (js/Worker. "paren-soup-compiler.js"))
          edit-history (mwm/create-edit-history)
          current-state (atom nil)
          refresh-instarepl-with-delay! (debounce refresh-instarepl! 300)
          events-chan (chan)]
      (set! (.-spellcheck paren-soup) false)
      (when-not content
        (throw (js/Error. "Can't find a div with class 'content'")))
      ; refresh the editor every time the state is changed
      (add-watch current-state :render
        (fn [_ _ _ state]
          (post-refresh-content! content events-chan (refresh-content! content state))
          (some-> numbers (refresh-numbers! (count (re-seq #"\n" (:text state)))))
          (some-> instarepl (refresh-instarepl-with-delay! content eval-worker))))
      ; initialize the editor
      (->> (init-state content true)
           (add-parinfer :paren)
           (adjust-state)
           (reset! current-state)
           (#(dissoc % :cropped-state))
           (mwm/update-edit-history! edit-history))
      ; set up event listeners
      (doto content
        (events/removeAll)
        (events/listen "keydown" (fn [e]
                                   (put! events-chan e)
                                   (when (or (key-name? e :undo-or-redo)
                                             (key-name? e :tab)
                                             (key-name? e :enter))
                                     (.preventDefault e))))
        (events/listen "keyup" #(put! events-chan %))
        (events/listen "cut" #(put! events-chan %))
        (events/listen "paste" #(put! events-chan %))
        (events/listen "mouseup" #(put! events-chan %)))
      ; run event loop
      (go
        (while true
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
                (let [state (init-state content true)
                      last-state (mwm/get-current-state edit-history)
                      diff (- (-> state :text count) (-> last-state :text count))]
                  (if (< diff -1)
                    (full-refresh! content current-state edit-history)
                    (->> (case (.-keyCode event)
                           13 (assoc state :indent-type :return)
                           9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                           (add-parinfer :indent state))
                         (adjust-state)
                         (reset! current-state)
                         (#(dissoc % :cropped-state))
                         (mwm/update-edit-history! edit-history)))))
              "cut"
              (full-refresh! content current-state edit-history)
              "paste"
              (full-refresh! content current-state edit-history)
              "mouseup"
              (mwm/update-cursor-position! edit-history (get-cursor-position content))
              "mouseenter"
              (show-error-message! paren-soup event)
              "mouseleave"
              (hide-error-messages! paren-soup)
              nil)))))))

(defn init-debug! []
  (.log js/console (with-out-str (time (with-fn-validation (init!))))))

(set! (.-onload js/window) init!)

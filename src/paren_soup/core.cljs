(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.string :refer [join replace]]
            [goog.events :as events]
            [goog.functions :refer [debounce]]
            [goog.string :refer [format]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [mistakes-were-made.core :as mwm]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn show-error-message!
  "Shows a popup with an error message."
  [parent-elem event]
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
  [parent-elem]
  (doseq [elem (-> parent-elem (.querySelectorAll ".error-text") array-seq)]
    (.removeChild parent-elem elem)))

(defn elems->locations
  "Returns the location of each elem."
  [elems top-offset]
  (loop [i 0
         locations (transient [])]
    (if-let [elem (get elems i)]
      (let [top (-> elem .-offsetTop (- top-offset))
            height (-> elem .-offsetHeight)]
        (recur (inc i) (conj! locations {:top top :height height})))
      (persistent! locations))))

(defn results->html
  "Returns HTML for the given eval results."
  [results locations]
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

(defn get-collections
  "Returns collections from the given DOM node."
  [element]
  (vec (for [elem (-> element .-children array-seq)
             :let [classes (.-classList elem)]
             :when (or (.contains classes "collection")
                       (.contains classes "symbol"))]
         elem)))

(def ^:const rainbow-count 10)

(defn rainbow-delimiters
  "Returns a map of elements and class names."
  ([parent level]
   (persistent! (rainbow-delimiters parent level (transient {}))))
  ([parent level m]
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

(defn line-numbers
  "Adds line numbers to the numbers."
  [line-count]
  (join (for [i (range line-count)]
          (str "<div>" (inc i) "</div>"))))

(defn get-parents
  "Returns the parents of the given node."
  [node]
  (loop [node node
         nodes '()]
    (if-let [parent (.-parentElement node)]
      (if (.contains (.-classList parent) "collection")
        (recur parent (conj nodes parent))
        (recur parent nodes))
      nodes)))

(defn text-node?
  [node]
  (= 3 (.-nodeType node)))

(defn error-node?
  [node]
  (some-> node .-classList (.contains "error")))

(defn top-level?
  [node]
  (some-> node .-parentElement .-classList (.contains "content")))

(defn common-ancestor
  "Returns the common ancestor of the given nodes."
  [first-node second-node]
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

(defn get-selection
  "Returns the objects related to selection for the given element. If full-selection? is true,
it will use rangy instead of the native selection API in order to get the beginning and ending
of the selection (it is, however, much slower)."
  [element full-selection?]
  {:element element
   :cursor-position
   (cond
     full-selection?
     (let [selection (.getSelection js/rangy)
           ranges (.saveCharacterRanges selection element)]
       (if-let [char-range (some-> ranges (aget 0) (aget "characterRange"))]
         [(aget char-range "start") (aget char-range "end")]
         [0 0]))
     (= 0 (.-rangeCount (.getSelection js/window)))
     [0 0]
     :else
     (let [selection (.getSelection js/window)
           range (.getRangeAt selection 0)
           pre-caret-range (doto (.cloneRange range)
                             (.selectNodeContents element)
                             (.setEnd (.-endContainer range) (.-endOffset range)))
           pos (-> pre-caret-range .toString .-length)]
       [pos pos]))})

(defn get-cursor-position
  "Returns the cursor position."
  [element]
  (-> element (get-selection false) :cursor-position))

(defn set-cursor-position!
  "Moves the cursor to the specified position."
  [element position]
  (let [[start-pos end-pos] position
        selection (.getSelection js/rangy)
        char-range #js {:start start-pos :end end-pos}
        range #js {:characterRange char-range
                   :backward false
                   :characterOptions nil}
        ranges (array range)]
    (.restoreCharacterRanges selection element ranges)))

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers line-count]
  (set! (.-innerHTML numbers) (line-numbers line-count)))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl content eval-worker]
  (let [elems (get-collections content)
        locations (elems->locations elems (.-offsetTop instarepl))
        forms (into-array (map #(-> % .-textContent (replace \u00a0 " ")) elems))]
    (set! (.-onmessage eval-worker)
          (fn [e]
            (let [results (.-data e)]
              (when (.-parentElement instarepl)
                (set! (.-innerHTML instarepl)
                      (results->html results locations))))))
    (.postMessage eval-worker forms)))

(defn post-refresh-content!
  "Does additional work on the content after it is rendered."
  [content events-chan state]
  ; set the cursor position
  (if-let [crop (:cropped-state state)]
    (set-cursor-position! (:element crop) (:cursor-position crop))
    (set-cursor-position! content (:cursor-position state)))
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
  [content state]
  (if-let [crop (:cropped-state state)]
    (let [crop (refresh-content-element! crop)]
      ; if there were changes outside the node, we need to run it on the whole document instead
      (if (not= (:text state) (.-textContent content))
        (refresh-content! content (dissoc state :cropped-state))
        (assoc state :cropped-state crop)))
    (do
      (set! (.-innerHTML content) (hs/code->html (:text state)))
      state)))

(defn refresh-console-content! [content state console-start-num clj?]
  (set! (.-innerHTML content)
    (if clj?
      (let [pre-text (subs (:text state) 0 console-start-num)
            post-text (subs (:text state) console-start-num)]
        (str pre-text (hs/code->html post-text)))
      (:text state)))
  (dissoc state :cropped-state))

(defn add-parinfer-after-console-start [console-start-num mode-type state]
  (let [pre-text (subs (:text state) 0 console-start-num)
        post-text (subs (:text state) console-start-num)
        cleared-text (str (replace pre-text #"[^\r^\n]" " ") post-text)
        temp-state (assoc state :text cleared-text)
        temp-state (cp/add-parinfer mode-type temp-state)
        new-text (str pre-text (subs (:text temp-state) console-start-num))]
    (assoc state :text new-text)))

(defn add-parinfer
  "Adds parinfer to the state."
  [enable? console-start-num mode-type state]
  (if enable?
    (let [state (if (pos? console-start-num)
                  (add-parinfer-after-console-start console-start-num mode-type state)
                  (cp/add-parinfer mode-type state))]
      (if-let [crop (:cropped-state state)]
        (assoc state :cropped-state
          (merge crop (cp/add-parinfer mode-type crop)))
        state))
    state))

(defn add-newline [{:keys [text] :as state}]
  (if-not (= \newline (last text))
    (assoc state :text (str text \newline))
    state))

(defn adjust-indent
  "Adds a newline and indentation to the state if necessary."
  [enable? state]
  (if enable?
    (let [{:keys [indent-type cropped-state]} state
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
      state)
    state))

(defn init-state
  "Returns the editor's state. If full-selection? is true, it will try to save
the entire selection rather than just the cursor position."
  [content crop? full-selection?]
  (let [selection (.getSelection js/rangy)
        anchor (.-anchorNode selection)
        focus (.-focusNode selection)
        parent (when (and anchor focus)
                 (common-ancestor anchor focus))
        state {:cursor-position (-> content (get-selection full-selection?) :cursor-position)
               :text (.-textContent content)}]
    (if-let [cropped-selection (some-> parent (get-selection false))]
      (if crop?
        (assoc state :cropped-state
          (assoc cropped-selection :text (.-textContent parent)))
        state)
      state)))

(defn key-name?
  "Returns true if the supplied key event involves the key(s) described by key-name."
  [event key-name]
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

(defn update-edit-history! [edit-history state]
  (try
    (mwm/update-edit-history! edit-history (dissoc state :cropped-state))
    state
    (catch js/Error _ (mwm/get-current-state edit-history))))

(defn prevent-default? [event]
  (or (key-name? event :undo-or-redo)
      (key-name? event :tab)
      (key-name? event :enter)))

(defn ^:export init [paren-soup opts]
  (.init js/rangy)
  (let [{:keys [change-callback disable-undo-redo? history-limit console-callback disable-clj?]
         :or {history-limit 100}}
        (js->clj opts :keywordize-keys true)
        clj? (not disable-clj?)
        editor? (not console-callback)
        content (.querySelector paren-soup ".content")
        eval-worker (try (js/Worker. "paren-soup-compiler.js")
                      (catch js/Error _))
        edit-history (mwm/create-edit-history)
        current-state (atom nil)
        refresh-instarepl-with-delay! (debounce refresh-instarepl! 300)
        events-chan (chan)
        undo! #(some->> edit-history mwm/undo! add-newline (adjust-indent editor?) (reset! current-state))
        redo! #(some->> edit-history mwm/redo! add-newline (adjust-indent editor?) (reset! current-state))
        console-start (atom 0)
        update-cursor-position! (fn [position]
                                  (try
                                    (mwm/update-cursor-position! edit-history position)
                                    (catch js/Error _
                                      (let [start @console-start]
                                        (set-cursor-position! content [start start])
                                        (mwm/update-cursor-position! edit-history [start start])))))
        reset-edit-history! (fn [start]
                              (reset! console-start start)
                              (set-cursor-position! content [start start])
                              (let [new-edit-history (mwm/create-edit-history)
                                    state {:cursor-position [start start]
                                           :text (.-textContent content)}]
                                (update-edit-history! new-edit-history state)
                                (reset! edit-history @new-edit-history)))
        append-text! (fn [text]
                       (let [node (.createTextNode js/document text)
                             _ (.appendChild content node)
                             all-text (.-textContent content)]
                         (reset-edit-history! (count all-text))))
        full-refresh! (fn []
                        (->> (init-state content false false)
                             (add-parinfer clj? @console-start :indent)
                             (add-newline)
                             (adjust-indent editor?)
                             (update-edit-history! edit-history)
                             (reset! current-state)))]
    (set! (.-spellcheck paren-soup) false)
    (when-not content
      (throw (js/Error. "Can't find a div with class 'content'")))
    ; set edit history limit
    (swap! edit-history assoc :limit history-limit)
    ; refresh the editor every time the state is changed
    (add-watch current-state :render
      (fn [_ _ _ state]
        (post-refresh-content! content events-chan
          (if editor?
            (refresh-content! content state)
            (refresh-console-content! content state @console-start clj?)))
        (when editor?
          (some-> (.querySelector paren-soup ".numbers")
                  (refresh-numbers! (count (re-seq #"\n" (:text state)))))
          (when clj?
            (some-> (.querySelector paren-soup ".instarepl")
                    (refresh-instarepl-with-delay! content eval-worker))))))
    ; in console mode, don't allow text before console-start to be edited
    (when-not editor?
      (set-validator! edit-history
        (fn [{:keys [current-state states]}]
          (if-let [state (get states current-state)]
            (-> state :cursor-position first (>= @console-start))
            true))))
    ; initialize the editor
    (when editor?
      (->> (init-state content true false)
           (add-parinfer clj? @console-start :paren)
           (add-newline)
           (adjust-indent editor?)
           (update-edit-history! edit-history)
           (reset! current-state)))
    ; set up event listeners
    (doto content
      (events/removeAll)
      (events/listen "keydown" (fn [e]
                                 (when (prevent-default? e)
                                   (.preventDefault e))
                                 (put! events-chan e)))
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
              (and (key-name? event :undo-or-redo) (not disable-undo-redo?))
              (if (.-shiftKey event) (redo!) (undo!))
              console-callback
              (cond
                (key-name? event :enter)
                (let [text (.-textContent content)
                      text (subs text 0 (dec (count text)))
                      entered-text (subs text @console-start)]
                  (reset-edit-history! (count text))
                  (console-callback entered-text)))
              (key-name? event :enter)
              (.execCommand js/document "insertHTML" false "\n"))
            "keyup"
            (cond
              (key-name? event :arrows)
              (update-cursor-position! (get-cursor-position content))
              (key-name? event :general)
              (let [state (init-state content true (= 9 (.-keyCode event)))
                    last-state (mwm/get-current-state edit-history)
                    diff (- (-> state :text count) (-> last-state :text count))]
                (if (< diff -1)
                  (full-refresh!)
                  (->> (case (.-keyCode event)
                         13 (assoc state :indent-type :return)
                         9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                         (add-parinfer clj? @console-start :indent state))
                       (add-newline)
                       (adjust-indent editor?)
                       (update-edit-history! edit-history)
                       (reset! current-state)))))
            "cut"
            (full-refresh!)
            "paste"
            (full-refresh!)
            "mouseup"
            (update-cursor-position! (get-cursor-position content))
            "mouseenter"
            (show-error-message! paren-soup event)
            "mouseleave"
            (hide-error-messages! paren-soup)
            nil)
          (when change-callback (change-callback event)))))
    ; return functions in map so our exported functions can call them
    {:undo! undo!
     :redo! redo!
     :can-undo? #(mwm/can-undo? edit-history)
     :can-redo? #(mwm/can-redo? edit-history)
     :append-text! append-text!
     :eval! (fn [form callback]
              (when-not eval-worker
                (throw (js/Error. "Can't find paren-soup-compiler.js")))
              (set! (.-onmessage eval-worker)
                (fn [e]
                  (let [results (.-data e)]
                    (callback (aget results 0)))))
              (.postMessage eval-worker (array form)))}))

(defn ^:export init-all []
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (init paren-soup #js {})))

(defn ^:export undo [{:keys [undo!]}] (undo!))
(defn ^:export redo [{:keys [redo!]}] (redo!))
(defn ^:export can-undo [{:keys [can-undo?]}] (can-undo?))
(defn ^:export can-redo [{:keys [can-redo?]}] (can-redo?))
(defn ^:export append-text [{:keys [append-text!]} text] (append-text! text))
(defn ^:export eval [{:keys [eval!]} form callback] (eval! form callback))

(defn init-debug []
  (.log js/console (with-out-str (time (init-all)))))

(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.string :refer [join replace trimr]]
            [goog.events :as events]
            [goog.functions :refer [debounce]]
            [goog.string :refer [format]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [mistakes-were-made.core :as mwm]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp]
            [paren-soup.console :as c])
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
  [element full-selection?]
  (-> element (get-selection full-selection?) :cursor-position))

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
    (let [show-error-icon! (fn [elem]
                             (set! (.-display (.-style elem)) "inline-block"))
          show-error-icon! (debounce show-error-icon! 1000)]
      (show-error-icon! elem))
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
                   (if (text-node? current-elem)
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
  (let [crop (:cropped-state state)
        errors? (->> (.querySelectorAll content ".error")
                     array-seq
                     (some #(= "inline-block" (-> % .-style .-display))))]
    (if (and crop (not errors?))
      (let [crop (refresh-content-element! crop)]
        ; if there were changes outside the node, we need to run it on the whole document instead
        (if (not= (:text state) (.-textContent content))
          (refresh-content! content (dissoc state :cropped-state))
          (assoc state :cropped-state crop)))
      (do
        (set! (.-innerHTML content) (hs/code->html (:text state)))
        (dissoc state :cropped-state)))))

(defn refresh-console-content! [content state console-start-num clj?]
  (set! (.-innerHTML content)
    (if clj?
      (let [pre-text (subs (:text state) 0 console-start-num)
            post-text (subs (:text state) console-start-num)]
        (str (hs/escape-html-str pre-text) (hs/code->html post-text)))
      (hs/escape-html-str (:text state))))
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

(defn update-edit-history! [edit-history state]
  (try
    (mwm/update-edit-history! edit-history (dissoc state :cropped-state))
    state
    (catch js/Error _ (mwm/get-current-state edit-history))))

(defn update-highlight! [content last-elem]
  (when-let [elem @last-elem]
    (set! (.-backgroundColor (.-style elem)) nil)
    (reset! last-elem nil))
  (when-let [elem (some-> js/rangy .getSelection .-anchorNode get-parents last)]
    (when-let [color (.getPropertyValue (.getComputedStyle js/window (.-firstChild elem)) "color")]
      (let [new-color (-> color (replace #"rgb\(" "") (replace #"\)" ""))]
        (set! (.-backgroundColor (.-style elem)) (str "rgba(" new-color ", 0.1)"))
        (reset! last-elem elem)))))

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
    :up-arrow
    (= (.-keyCode event) 38)
    :down-arrow
    (= (.-keyCode event) 40)
    :general
    (not (or (contains? #{0 ; invalid (possible webkit bug)
                          16 ; shift
                          17 ; ctrl
                          18 ; alt
                          91 93} ; meta
               (.-keyCode event))
             (.-ctrlKey event)
             (.-metaKey event)))
    false))

(defprotocol Editor
  (undo! [this])
  (redo! [this])
  (can-undo? [this])
  (can-redo? [this])
  (update-cursor-position! [this position])
  (reset-edit-history! [this start])
  (append-text! [this text])
  (enter! [this])
  (up! [this])
  (down! [this])
  (tab! [this])
  (refresh! [this state])
  (edit-and-refresh! [this state])
  (initialize! [this])
  (refresh-after-key-event! [this event])
  (refresh-after-cut-paste! [this])
  (eval! [this form callback]))

(defn create-editor [paren-soup content events-chan
                     {:keys [history-limit append-limit compiler-file console-callback disable-clj?]
                      :or {history-limit 100
                           append-limit 5000
                           compiler-file "paren-soup-compiler.js"}}]
  (let [clj? (not disable-clj?)
        editor? (not console-callback)
        eval-worker (try (js/Worker. compiler-file)
                      (catch js/Error _))
        edit-history (doto (mwm/create-edit-history)
                       (swap! assoc :limit history-limit))
        refresh-instarepl-with-delay! (debounce refresh-instarepl! 300)
        console-history (c/create-console-history)
        last-highlight-elem (atom nil)
        allow-tab? (atom false)]
    ; in console mode, don't allow text before console start to be edited
    (when-not editor?
      (set-validator! edit-history
        (fn [{:keys [current-state states]}]
          (if-let [state (get states current-state)]
            (-> state :cursor-position first (>= (c/get-console-start console-history)))
            true))))
    ; reify the protocol
    (reify Editor
      (undo! [this]
        (some->> edit-history mwm/undo! add-newline (adjust-indent editor?) (refresh! this)))
      (redo! [this]
        (some->> edit-history mwm/redo! add-newline (adjust-indent editor?) (refresh! this)))
      (can-undo? [this]
        (mwm/can-undo? edit-history))
      (can-redo? [this]
        (mwm/can-redo? edit-history))
      (update-cursor-position! [this position]
        (try
          (mwm/update-cursor-position! edit-history position)
          (catch js/Error _
            (when (apply = position)
              (let [start (c/get-console-start console-history)]
                (set-cursor-position! content [start start])
                (mwm/update-cursor-position! edit-history [start start])))))
        (update-highlight! content last-highlight-elem))
      (reset-edit-history! [this start]
        (c/update-console-start! console-history start)
        (set-cursor-position! content [start start])
        (let [new-edit-history (mwm/create-edit-history)
              state {:cursor-position [start start]
                     :text (.-textContent content)}]
          (update-edit-history! new-edit-history state)
          (reset! edit-history @new-edit-history)))
      (append-text! [this text]
        (let [node (.createTextNode js/document text)
              _ (.appendChild content node)
              all-text (.-textContent content)
              char-count (max 0 (- (count all-text) append-limit))
              new-all-text (subs all-text char-count)]
          (when (not= all-text new-all-text)
            (set! (.-textContent content) new-all-text))
          (reset-edit-history! this (count new-all-text))))
      (enter! [this]
        (if editor?
          (.execCommand js/document "insertHTML" false "\n")
          (let [text (trimr (.-textContent content))
                post-text (subs text (c/get-console-start console-history))]
            (reset-edit-history! this (count text))
            (c/update-console-history! console-history post-text)
            (console-callback post-text))))
      (up! [this]
        (when-not editor?
          (let [text (.-textContent content)
                pre-text (subs text 0 (c/get-console-start console-history))
                line (or (c/up! console-history) "")
                state {:cursor-position (get-cursor-position content false)
                       :text (str pre-text line \newline)}]
            (->> state
                 (update-edit-history! edit-history)
                 (refresh! this)))))
      (down! [this]
        (when-not editor?
          (let [text (.-textContent content)
                pre-text (subs text 0 (c/get-console-start console-history))
                line (or (c/down! console-history) "")
                state {:cursor-position (get-cursor-position content false)
                       :text (str pre-text line \newline)}]
            (->> state
                 (update-edit-history! edit-history)
                 (refresh! this)))))
      (tab! [this]
        ; on Windows, alt+tab causes the browser to receive the tab's keyup event
        ; this caused the code to be tabbed after using alt+tab
        ; this boolean atom will be set to true only on keydown in order to prevent this issue
        (when editor?
          (reset! allow-tab? true)))
      (refresh! [this state]
        (post-refresh-content! content events-chan
          (if editor?
            (refresh-content! content state)
            (refresh-console-content! content state (c/get-console-start console-history) clj?)))
        (when editor?
          (some-> (.querySelector paren-soup ".numbers")
                  (refresh-numbers! (count (re-seq #"\n" (:text state)))))
          (when clj?
            (some-> (.querySelector paren-soup ".instarepl")
                    (refresh-instarepl-with-delay! content eval-worker))))
        (update-highlight! content last-highlight-elem))
      (edit-and-refresh! [this state]
        (->> state
             (add-newline)
             (adjust-indent editor?)
             (update-edit-history! edit-history)
             (refresh! this)))
      (initialize! [this]
        (when editor?
          (->> (init-state content true false)
               (add-parinfer clj? (c/get-console-start console-history) :paren)
               (edit-and-refresh! this))))
      (refresh-after-key-event! [this event]
        (let [tab? (key-name? event :tab)
              state (init-state content true tab?)]
          (when-not (and tab? (not @allow-tab?))
            (edit-and-refresh! this
              (case (.-keyCode event)
                13 (assoc state :indent-type :return)
                9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                (add-parinfer clj? (c/get-console-start console-history) :indent state))))
          (when tab?
            (reset! allow-tab? false))))
      (refresh-after-cut-paste! [this]
        (->> (init-state content false false)
             (add-parinfer clj? (c/get-console-start console-history) (if editor? :indent :both))
             (edit-and-refresh! this)))
      (eval! [this form callback]
        (when-not eval-worker
          (throw (js/Error. "Can't find " + compiler-file)))
        (set! (.-onmessage eval-worker)
          (fn [e]
            (let [results (.-data e)]
              (callback (aget results 0)))))
        (.postMessage eval-worker (array form))))))

(defn prevent-default? [event opts]
  (or (key-name? event :undo-or-redo)
      (key-name? event :tab)
      (key-name? event :enter)
      (and (:console-callback opts)
           (or (key-name? event :up-arrow)
               (key-name? event :down-arrow)))))

(defn add-event-listeners! [content events-chan opts]
  (doto content
    (events/removeAll)
    (events/listen "keydown" (fn [e]
                               (when (prevent-default? e opts)
                                 (.preventDefault e))
                               (put! events-chan e)))
    (events/listen "keyup" #(put! events-chan %))
    (events/listen "cut" #(put! events-chan %))
    (events/listen "paste" #(put! events-chan %))
    (events/listen "mouseup" #(put! events-chan %))))

(defn ^:export init [paren-soup opts]
  (.init js/rangy)
  (let [opts (js->clj opts :keywordize-keys true)
        content (.querySelector paren-soup ".content")
        events-chan (chan)
        editor (create-editor paren-soup content events-chan opts)]
    (set! (.-spellcheck paren-soup) false)
    (when-not content
      (throw (js/Error. "Can't find a div with class 'content'")))
    (initialize! editor)
    ; set up event listeners
    (add-event-listeners! content events-chan opts)
    ; run event loop
    (go
      (while true
        (let [event (<! events-chan)]
          (case (.-type event)
            "keydown"
            (cond
              (and (key-name? event :undo-or-redo) (-> opts :disable-undo-redo? not))
              (if (.-shiftKey event) (redo! editor) (undo! editor))
              (key-name? event :enter)
              (enter! editor)
              (key-name? event :up-arrow)
              (up! editor)
              (key-name? event :down-arrow)
              (down! editor)
              (key-name? event :tab)
              (tab! editor))
            "keyup"
            (cond
              (key-name? event :arrows)
              (update-cursor-position! editor (get-cursor-position content false))
              (key-name? event :general)
              (refresh-after-key-event! editor event))
            "cut"
            (refresh-after-cut-paste! editor)
            "paste"
            (refresh-after-cut-paste! editor)
            "mouseup"
            (update-cursor-position! editor (get-cursor-position content (some? (:console-callback opts))))
            "mouseenter"
            (show-error-message! paren-soup event)
            "mouseleave"
            (hide-error-messages! paren-soup)
            nil)
          (some-> opts :change-callback (apply [event])))))
    ; return editor
    editor))

(defn ^:export init-all []
  (doseq [paren-soup (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (init paren-soup #js {})))

(defn ^:export undo [editor] (undo! editor))
(defn ^:export redo [editor] (redo! editor))
(defn ^:export can-undo [editor] (can-undo? editor))
(defn ^:export can-redo [editor] (can-redo? editor))
(defn ^:export append-text [editor text] (append-text! editor text))
(defn ^:export eval [editor form callback] (eval! editor form callback))

(defn init-debug []
  (.log js/console (with-out-str (time (init-all)))))


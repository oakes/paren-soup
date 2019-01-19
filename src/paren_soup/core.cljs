(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.string :refer [join replace trimr]]
            [goog.events :as events]
            [goog.functions :refer [debounce]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [mistakes-were-made.core :as mwm :refer [atom?]]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp]
            [paren-soup.console :as console]
            [paren-soup.instarepl :as ir]
            [paren-soup.dom :as dom]
            [clojure.spec.alpha :as s :refer [fdef]]
            [goog.labs.userAgent.browser :as browser])
  (:refer-clojure :exclude [eval])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def channel? #(instance? cljs.core.async.impl.channels.ManyToManyChannel %))
(def transient-map? #(or (instance? cljs.core/TransientArrayMap %)
                         (instance? cljs.core/TransientHashMap %)))
(def elem? #(instance? js/Element %))
(def obj? #(instance? js/Object %))

(fdef show-error-message!
  :args (s/cat :parent-elem elem? :event obj?))

(defn show-error-message!
  "Shows a popup with an error message."
  [parent-elem event]
  (let [elem (.-target event)
        x (.-clientX event)
        y (.-clientY event)
        popup (.createElement js/document "div")]
    (set! (.-textContent popup) (-> elem .-dataset .-message))
    (set! (.-top (.-style popup)) (str y "px"))
    (set! (.-left (.-style popup)) (str x "px"))
    (set! (.-className popup) "error-text")
    (.appendChild parent-elem popup)))

(fdef hide-error-messages!
  :args (s/cat :parent-elem elem?))

(defn hide-error-messages!
  "Hides all error popups."
  [parent-elem]
  (doseq [elem (-> parent-elem (.querySelectorAll ".error-text") array-seq)]
    (.removeChild parent-elem elem)))

(def ^:const rainbow-count 5)

(fdef rainbow-delimiters
  :args (s/alt
          :two-args (s/cat :parent elem? :level number?)
          :three-args (s/cat :parent elem? :level number? :m transient-map?))
  :ret (s/or :two-args map? :three-args transient-map?))

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

(fdef line-numbers
  :args (s/cat :line-count number?)
  :ret string?)

(defn line-numbers
  "Adds line numbers to the numbers."
  [line-count]
  (join (for [i (range line-count)]
          (str "<div>" (inc i) "</div>"))))

(fdef refresh-numbers!
  :args (s/cat :numbers elem? :line-count number?))

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers line-count]
  (set! (.-innerHTML numbers) (line-numbers line-count)))

(fdef refresh-instarepl!
  :args (s/cat :instarepl elem? :content elem? :compiler-fn fn? :limit number?))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl content compiler-fn limit]
  (let [elems (ir/get-collections content)
        locations (ir/elems->locations elems (.-offsetTop instarepl))
        forms (->> elems
                   (map ir/collection->content)
                   (map #(replace % \u00a0 " ")))]
    (compiler-fn forms
      (fn [results]
        (when (.-parentElement instarepl)
          (set! (.-innerHTML instarepl)
                (ir/results->html results locations limit)))))))

(fdef post-refresh-content!
  :args (s/cat :content elem? :events-chan channel? :focus? boolean? :state map?))

(defn post-refresh-content!
  "Does additional work on the content after it is rendered."
  [content events-chan focus? {:keys [cropped-state] :as state}]
  ; set the cursor position
  (when focus?
    (if (some->> cropped-state :element (.contains content))
      (some-> cropped-state :element (dom/set-cursor-position! (:cursor-position cropped-state)))
      (if (and (:selection-change? state) (:original-cursor-position state))
        (dom/set-cursor-position! content (:original-cursor-position state))
        (dom/set-cursor-position! content (:cursor-position state)))))
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

(fdef refresh-content-element!
  :args (s/cat :cropped-state map?)
  :ret map?)

(defn refresh-content-element!
  "Replaces a single node in the content, and siblings if necessary."
  [{:keys [element text] :as cropped-state}]
  (let [parent (.-parentElement element)
        ; find the last element to refresh
        last-elem (.-lastChild parent)
        last-error (loop [current-elem last-elem]
                     (cond
                       (dom/error-node? current-elem)
                       current-elem
                       (or (nil? current-elem)
                           (= element current-elem))
                       nil
                       :else
                       (recur (.-previousSibling current-elem))))
        last-elem-to-refresh (when last-error
                               (loop [current-elem last-error]
                                 (if-let [sibling (.-nextSibling current-elem)]
                                   (if (dom/coll-node? sibling)
                                     current-elem
                                     (recur sibling))
                                   current-elem)))
        ; find all elements that should be refreshed
        old-elems (loop [elems [element]
                         current-elem element]
                    (cond
                      (= last-elem-to-refresh current-elem)
                      elems
                      (or (some? last-elem-to-refresh)
                          (dom/text-node? current-elem))
                      (if-let [sibling (.-nextSibling current-elem)]
                        (recur (conj elems sibling) sibling)
                        elems)
                      :else
                      elems))
        ; add old elems' text to the string
        _ (set! (.-textContent element) text)
        text (join (map #(.-textContent %) old-elems))
        ; create temporary element
        temp-elem (.createElement js/document "span")
        _ (set! (.-innerHTML temp-elem) (hs/code->html text))
        ; collect elements
        new-elems (doall
                    (for [i (range (-> temp-elem .-childNodes .-length))]
                      (-> temp-elem .-childNodes (.item i))))]
    ; insert the new nodes
    (doseq [new-elem new-elems]
      (.insertBefore parent new-elem element))
    ; remove the old nodes
    (doseq [old-elem old-elems]
      (.removeChild parent old-elem))
    (assoc cropped-state :element (first new-elems))))

(fdef refresh-content!
  :args (s/cat :content elem? :state map?)
  :ret map?)

(defn refresh-content!
  "Refreshes the content."
  [content state]
  (if-let [crop (some-> state :cropped-state refresh-content-element!)]
    ; if there were changes outside the node, we need to run it on the whole document instead
    (if (not= (:text state) (.-textContent content))
      (refresh-content! content (dissoc state :cropped-state))
      (assoc state :cropped-state crop))
    (do
      (set! (.-innerHTML content) (hs/code->html (:text state)))
      (dissoc state :cropped-state))))

(fdef refresh-console-content!
  :args (s/cat :content elem? :state map? :console-start-num number? :clj? boolean?)
  :ret map?)

(defn refresh-console-content! [content state console-start-num clj?]
  (set! (.-innerHTML content)
    (if clj?
      (let [pre-text (subs (:text state) 0 console-start-num)
            post-text (subs (:text state) console-start-num)]
        (str (hs/escape-html-str pre-text) (hs/code->html post-text)))
      (hs/escape-html-str (:text state))))
  state)

(fdef add-parinfer-after-console-start
  :args (s/cat :console-start-num number? :state map?)
  :ret map?)

(defn add-parinfer-after-console-start [console-start-num state]
  (let [pre-text (subs (:text state) 0 console-start-num)
        post-text (subs (:text state) console-start-num)
        cleared-text (str (replace pre-text #"[^\r^\n]" " ") post-text)
        temp-state (assoc state :text cleared-text)
        temp-state (cp/add-parinfer :both temp-state)
        new-text (str pre-text (subs (:text temp-state) console-start-num))]
    (assoc state :text new-text)))

(fdef add-parinfer
  :args (s/cat :enable? boolean? :console-start-num number? :state map?)
  :ret map?)

(defn add-parinfer [enable? console-start-num state]
  (if enable?
    (let [cropped-state (:cropped-state state)
          indent-type (:indent-type state)
          state (cond
                  (pos? console-start-num)
                  (add-parinfer-after-console-start console-start-num state)
                  indent-type
                  (cp/add-indent state)
                  :else
                  (cp/add-parinfer :paren state))]
      (if (and cropped-state indent-type)
        (assoc state :cropped-state
          (merge cropped-state (cp/add-indent (assoc cropped-state :indent-type indent-type))))
        state))
    state))

(fdef add-newline
  :args (s/cat :state map?)
  :ret map?)

(defn add-newline [{:keys [text] :as state}]
  (if-not (= \newline (last text))
    (assoc state :text (str text \newline))
    state))

(fdef init-state
  :args (s/cat :content elem? :crop? boolean? :full-selection? boolean?)
  :ret map?)

(defn init-state
  "Returns the editor's state. If full-selection? is true, it will try to save
the entire selection rather than just the cursor position."
  [content crop? full-selection?]
  (let [selection (.getSelection js/rangy)
        anchor (.-anchorNode selection)
        focus (.-focusNode selection)
        parent (when (and anchor focus)
                 (dom/common-ancestor anchor focus))
        state {:cursor-position (-> content (dom/get-selection full-selection?) :cursor-position)
               :text (.-textContent content)}]
    (if-let [cropped-selection (some-> parent (dom/get-selection false))]
      (if crop?
        (assoc state :cropped-state
          (assoc cropped-selection :text (.-textContent parent)))
        state)
      state)))

(fdef update-edit-history!
  :args (s/cat :*edit-history atom? :state map?)
  :ret map?)

(defn update-edit-history! [*edit-history state]
  (try
    (mwm/update-edit-history! *edit-history
      (if (:selection-change? state)
        state
        (dissoc state :cropped-state)))
    state
    (catch js/Error _ (mwm/get-current-state *edit-history))))

(fdef update-highlight!
  :args (s/cat :content elem? :last-elem atom?))

(defn update-highlight! [content *last-elem]
  (when-let [elem @*last-elem]
    (set! (.-backgroundColor (.-style elem)) nil)
    (reset! *last-elem nil))
  (when-let [elem (dom/get-focused-form)]
    (when-let [color (.getPropertyValue (.getComputedStyle js/window (.-firstChild elem)) "color")]
      (let [new-color (-> color (replace #"rgb\(" "") (replace #"\)" ""))]
        (set! (.-backgroundColor (.-style elem)) (str "rgba(" new-color ", 0.1)"))
        (reset! *last-elem elem)))))

(fdef key-code
  :args (s/cat :event obj?)
  :ret integer?)

(defn key-code [event]
  (let [code (.-keyCode event)]
    (if (pos? code) code (.-which event))))

(fdef key-name?
  :args (s/cat :event obj? :key-name keyword?)
  :ret boolean?)

(defn key-name?
  "Returns true if the supplied key event involves the key(s) described by key-name."
  [event key-name]
  (let [code (key-code event)]
    (case key-name
      :undo-or-redo
      (and (or (.-metaKey event) (.-ctrlKey event))
         (= code 90))
      :tab
      (= code 9)
      :enter
      (= code 13)
      :arrows
      (contains? #{37 38 39 40} code)
      :up-arrow
      (= code 38)
      :down-arrow
      (= code 40)
      :general
      (not (or (contains? #{0 ; invalid
                            16 ; shift
                            17 ; ctrl
                            18 ; alt
                            91 93} ; meta
                 code)
               (.-ctrlKey event)
               (.-metaKey event)))
      false)))

(defprotocol Editor
  (undo! [this])
  (redo! [this])
  (can-undo? [this])
  (can-redo? [this])
  (update-cursor-position! [this position])
  (reset-edit-history! [this start])
  (append-text! [this text])
  (enter! [this])
  (up! [this alt?])
  (down! [this alt?])
  (tab! [this])
  (refresh! [this state])
  (edit-and-refresh! [this state])
  (initialize! [this])
  (refresh-after-key-event! [this event])
  (refresh-after-cut-paste! [this])
  (eval! [this form callback]))

(fdef create-editor
  :args (s/cat :paren-soup elem? :content elem? :events-chan channel? :opts map?)
  :ret #(satisfies? Editor %))

(defn create-editor [ps content events-chan
                     {:keys [history-limit append-limit
                             compiler-fn console-callback
                             disable-clj? edit-history
                             focus?]
                      :or {history-limit 100
                           append-limit 5000
                           focus? false}}]
  (let [clj? (not disable-clj?)
        editor? (not console-callback)
        compiler-fn (or compiler-fn (ir/create-compiler-fn))
        *edit-history (doto (or edit-history (mwm/create-edit-history))
                        (swap! assoc :limit history-limit))
        refresh-instarepl-with-delay! (debounce refresh-instarepl! 300)
        *console-history (console/create-console-history)
        *last-highlight-elem (atom nil)
        *allow-tab? (atom false)
        *skip-refresh? (atom false)
        *first-refresh? (atom true)]
    ; in console mode, don't allow text before console start to be edited
    (when-not editor?
      (set-validator! *edit-history
        (fn [{:keys [current-state states]}]
          (if-let [state (get states current-state)]
            (-> state :cursor-position first (>= (console/get-console-start *console-history)))
            true))))
    ; reify the protocol
    (reify Editor
      (undo! [this]
        (some->> *edit-history mwm/undo! (refresh! this))
        (dom/scroll-to-nearest-elem))
      (redo! [this]
        (some->> *edit-history mwm/redo! (refresh! this))
        (dom/scroll-to-nearest-elem))
      (can-undo? [this]
        (mwm/can-undo? *edit-history))
      (can-redo? [this]
        (mwm/can-redo? *edit-history))
      (update-cursor-position! [this position]
        (try
          (mwm/update-cursor-position! *edit-history position)
          (catch js/Error _
            (when (apply = position)
              (let [start (console/get-console-start *console-history)]
                (dom/set-cursor-position! content [start start])
                (mwm/update-cursor-position! *edit-history [start start])))))
        (update-highlight! content *last-highlight-elem))
      (reset-edit-history! [this start]
        (console/update-console-start! *console-history start)
        (dom/set-cursor-position! content [start start])
        (let [*new-edit-history (mwm/create-edit-history)
              state {:cursor-position [start start]
                     :text (.-textContent content)}]
          (update-edit-history! *new-edit-history state)
          (reset! *edit-history @*new-edit-history)))
      (append-text! [this text]
        (let [node (.createTextNode js/document text)
              _ (.appendChild content node)
              all-text (.-textContent content)
              char-count (max 0 (- (count all-text) append-limit))
              new-all-text (subs all-text char-count)
              ; if text ends with a newline, it will be ignored,
              ; so we need to account for that
              ; see: https://stackoverflow.com/q/43492826
              char-count (if (.endsWith new-all-text "\n")
                           (dec (count new-all-text))
                           (count new-all-text))]
          (when (not= all-text new-all-text)
            (set! (.-textContent content) new-all-text))
          (reset-edit-history! this char-count)))
      (enter! [this]
        (if editor?
          ; the execCommand technique doesn't work in Edge, so we use
          ; insert-text! instead. we also need to manually move the cursor,
          ; and then prevent the refresh from happening.
          ; this is not ideal, as it means we will lose auto-indentation,
          ; but at least we are closer to supporting Edge than we were before.
          (if (or (browser/isEdge) (browser/isIE))
            (let [pos (dom/get-cursor-position content false)]
              (dom/insert-text! "\n")
              (dom/set-cursor-position! content (mapv inc pos))
              (reset! *skip-refresh? true))
            (.execCommand js/document "insertHTML" false "\n"))
          (let [text (trimr (.-textContent content))
                post-text (subs text (console/get-console-start *console-history))]
            (reset-edit-history! this (count text))
            (console/update-console-history! *console-history post-text)
            (console-callback post-text))))
      (up! [this alt?]
        (if alt?
          (when-let [elem (dom/get-focused-form)]
            (when-let [state (mwm/get-current-state *edit-history)]
              (when-let [; if elem was already selected, try selecting parent
                         elem (if (and (:selection-change? state)
                                       (= elem (some-> state :cropped-state :element)))
                                (dom/get-parent elem "collection")
                                elem)]
                (let [text (.-textContent elem)
                      pos [0 (count text)]]
                  (dom/set-cursor-position! elem pos)
                  (update-edit-history! *edit-history
                    (assoc state
                      :selection-change? true
                      :cropped-state {:cursor-position pos
                                      :text text
                                      :element elem}))
                  (update-highlight! content *last-highlight-elem)))))
          (when-not editor?
            (let [text (.-textContent content)
                  start (console/get-console-start *console-history)
                  pre-text (subs text 0 (console/get-console-start *console-history))
                  line (or (console/up! *console-history) "")
                  state {:cursor-position [start start]
                         :text (str pre-text line \newline)}]
              (->> state
                   (update-edit-history! *edit-history)
                   (refresh! this))))))
      (down! [this alt?]
        (if alt?
          (when (:selection-change? (mwm/get-current-state *edit-history))
            (undo! this))
          (when-not editor?
            (let [text (.-textContent content)
                  start (console/get-console-start *console-history)
                  pre-text (subs text 0 start)
                  line (or (console/down! *console-history) "")
                  state {:cursor-position [start start]
                         :text (str pre-text line \newline)}]
              (->> state
                   (update-edit-history! *edit-history)
                   (refresh! this))))))
      (tab! [this]
        ; on Windows, alt+tab causes the browser to receive the tab's keyup event
        ; this caused the code to be tabbed after using alt+tab
        ; this boolean atom will be set to true only on keydown in order to prevent this issue
        (when editor?
          (reset! *allow-tab? true)))
      (refresh! [this state]
        (if @*skip-refresh?
          (reset! *skip-refresh? false)
          (post-refresh-content! content events-chan (or focus? (not @*first-refresh?))
            (cond
              (:selection-change? state) state
              editor? (refresh-content! content state)
              :else (refresh-console-content! content state (console/get-console-start *console-history) clj?))))
        (reset! *first-refresh? false)
        (when editor?
          (some-> (.querySelector ps ".numbers")
                  (refresh-numbers! (count (re-seq #"\n" (:text state)))))
          (when clj?
            (when-let [elem (.querySelector ps ".instarepl")]
              (when-not (-> elem .-style .-display (= "none"))
                (refresh-instarepl-with-delay! elem content compiler-fn append-limit)))))
        (update-highlight! content *last-highlight-elem))
      (edit-and-refresh! [this state]
        (->> state
             (add-newline)
             (add-parinfer clj? (console/get-console-start *console-history))
             (update-edit-history! *edit-history)
             (refresh! this)))
      (initialize! [this]
        (when editor?
          (->> (init-state content false false)
               (edit-and-refresh! this))))
      (refresh-after-key-event! [this event]
        (let [tab? (key-name? event :tab)
              state (init-state content editor? tab?)]
          (when-not (and tab? (not @*allow-tab?))
            (edit-and-refresh! this
              (case (key-code event)
                13 (assoc state :indent-type :return)
                9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                (assoc state :indent-type :normal))))
          (when tab?
            (reset! *allow-tab? false))))
      (refresh-after-cut-paste! [this]
        (let [html (.-innerHTML content)
              insert-newlines? (-> html (.indexOf "</tr>") (> 0))
              crop? (and editor? (not insert-newlines?))]
          (when insert-newlines?
            (set! (.-innerHTML content) (replace html "</tr>" \newline)))
          (edit-and-refresh! this (assoc (init-state content crop? false) :indent-type :normal))))
      (eval! [this form callback]
        (compiler-fn [form] #(callback (first %)))))))

(fdef prevent-default?
  :args (s/cat :event obj? :opts map?)
  :ret boolean?)

(defn prevent-default? [event opts]
  (boolean
    (or (key-name? event :undo-or-redo)
        (key-name? event :tab)
        (key-name? event :enter)
        (and (or (:console-callback opts)
                 (.-altKey event))
             (or (key-name? event :up-arrow)
                 (key-name? event :down-arrow))))))

(fdef add-event-listeners!
  :args (s/cat :content elem? :events-chan channel? :opts map?))

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

(fdef init
  :args (s/cat :paren-soup elem? :opts obj?))

(defn ^:export init [ps opts]
  (.init js/rangy)
  (let [opts (js->clj opts :keywordize-keys true)
        content (.querySelector ps ".content")
        events-chan (chan)
        editor (create-editor ps content events-chan opts)]
    (set! (.-spellcheck ps) false)
    (when-not content
      (throw (js/Error. "Can't find a div with class 'content'")))
    (initialize! editor)
    ; set up event listeners
    (add-event-listeners! content events-chan opts)
    ; run event loop
    (go
      (while true
        (let [event (<! events-chan)]
          (when-not (some-> opts :before-change-callback (#(% event)))
            (case (.-type event)
              "keydown"
              (cond
                (and (key-name? event :undo-or-redo) (-> opts :disable-undo-redo? not))
                (if (.-shiftKey event) (redo! editor) (undo! editor))
                (key-name? event :enter)
                (enter! editor)
                (key-name? event :up-arrow)
                (up! editor (.-altKey event))
                (key-name? event :down-arrow)
                (down! editor (.-altKey event))
                (key-name? event :tab)
                (tab! editor))
              "keyup"
              (cond
                (key-name? event :arrows)
                (update-cursor-position! editor
                  (dom/get-cursor-position content false))
                (key-name? event :general)
                (refresh-after-key-event! editor event))
              "cut"
              (refresh-after-cut-paste! editor)
              "paste"
              (refresh-after-cut-paste! editor)
              "mouseup"
              (update-cursor-position! editor
                (dom/get-cursor-position content (some? (:console-callback opts))))
              "mouseenter"
              (show-error-message! ps event)
              "mouseleave"
              (hide-error-messages! ps)
              nil)
            (some-> opts :change-callback (#(% event)))))))
    ; return editor
    editor))

(fdef init-all
  :args (s/cat))

(defn ^:export init-all []
  (doseq [ps (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (init ps #js {})))

(defn ^:export undo [editor] (undo! editor))
(defn ^:export redo [editor] (redo! editor))
(defn ^:export can-undo [editor] (can-undo? editor))
(defn ^:export can-redo [editor] (can-redo? editor))
(defn ^:export append-text [editor text] (append-text! editor text))
(defn ^:export eval [editor form callback] (eval! editor form callback))
(defn ^:export debounce-function [f millis] (debounce f millis))
(defn ^:export focused-text [] (some-> (dom/get-focused-form) .-textContent))
(defn ^:export selected-text []
  (let [s (-> js/window .getSelection .toString)]
    (when-not (empty? s) s)))


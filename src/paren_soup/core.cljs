(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [clojure.string :refer [join replace trimr]]
            [goog.events :as events]
            [goog.functions :refer [debounce]]
            [cljsjs.rangy-core]
            [cljsjs.rangy-textrange]
            [mistakes-were-made.core :as mwm]
            [html-soup.core :as hs]
            [cross-parinfer.core :as cp]
            [paren-soup.console :as console]
            [paren-soup.instarepl :as ir]
            [paren-soup.dom :as dom]
            [goog.dom :as gdom])
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

(def ^:const rainbow-count 5)

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

(defn refresh-numbers!
  "Refreshes the line numbers."
  [numbers line-count]
  (set! (.-innerHTML numbers) (line-numbers line-count)))

(defn refresh-instarepl!
  "Refreshes the InstaREPL."
  [instarepl content compiler-fn]
  (let [elems (ir/get-collections content)
        locations (ir/elems->locations elems (.-offsetTop instarepl))
        forms (->> elems
                   (map ir/collection->content)
                   (map #(replace % \u00a0 " ")))]
    (compiler-fn forms
      (fn [results]
        (when (.-parentElement instarepl)
          (set! (.-innerHTML instarepl)
                (ir/results->html results locations)))))))

(defn post-refresh-content!
  "Does additional work on the content after it is rendered."
  [content events-chan state]
  ; set the cursor position
  (if-let [crop (:cropped-state state)]
    (dom/set-cursor-position! (:element crop) (:cursor-position crop))
    (dom/set-cursor-position! content (:cursor-position state)))
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
  (let [{:keys [element text]} cropped-state
        parent (.-parentElement element)
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
        _ (gdom/setTextContent element text)
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
      (dissoc state :cropped-state))))

(defn refresh-console-content! [content state console-start-num clj?]
  (set! (.-innerHTML content)
    (if clj?
      (let [pre-text (subs (:text state) 0 console-start-num)
            post-text (subs (:text state) console-start-num)]
        (str (hs/escape-html-str pre-text) (hs/code->html post-text)))
      (hs/escape-html-str (:text state))))
  state)

(defn add-parinfer-after-console-start [console-start-num state]
  (let [pre-text (subs (:text state) 0 console-start-num)
        post-text (subs (:text state) console-start-num)
        cleared-text (str (replace pre-text #"[^\r^\n]" " ") post-text)
        temp-state (assoc state :text cleared-text)
        temp-state (cp/add-parinfer :both state)
        new-text (str pre-text (subs (:text temp-state) console-start-num))]
    (assoc state :text new-text)))

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

(defn add-newline [{:keys [text] :as state}]
  (if-not (= \newline (last text))
    (assoc state :text (str text \newline))
    state))

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

(defn update-edit-history! [edit-history state]
  (try
    (mwm/update-edit-history! edit-history (dissoc state :cropped-state))
    state
    (catch js/Error _ (mwm/get-current-state edit-history))))

(defn update-highlight! [content last-elem]
  (when-let [elem @last-elem]
    (set! (.-backgroundColor (.-style elem)) nil)
    (reset! last-elem nil))
  (when-let [elem (dom/get-focused-form)]
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
                     {:keys [history-limit append-limit compiler-fn console-callback disable-clj?]
                      :or {history-limit 100
                           append-limit 5000}}]
  (let [clj? (not disable-clj?)
        editor? (not console-callback)
        compiler-fn (or compiler-fn (ir/create-compiler-fn))
        edit-history (doto (mwm/create-edit-history)
                       (swap! assoc :limit history-limit))
        refresh-instarepl-with-delay! (debounce refresh-instarepl! 300)
        console-history (console/create-console-history)
        last-highlight-elem (atom nil)
        allow-tab? (atom false)]
    ; in console mode, don't allow text before console start to be edited
    (when-not editor?
      (set-validator! edit-history
        (fn [{:keys [current-state states]}]
          (if-let [state (get states current-state)]
            (-> state :cursor-position first (>= (console/get-console-start console-history)))
            true))))
    ; reify the protocol
    (reify Editor
      (undo! [this]
        (some->> edit-history mwm/undo! (refresh! this)))
      (redo! [this]
        (some->> edit-history mwm/redo! (refresh! this)))
      (can-undo? [this]
        (mwm/can-undo? edit-history))
      (can-redo? [this]
        (mwm/can-redo? edit-history))
      (update-cursor-position! [this position]
        (try
          (mwm/update-cursor-position! edit-history position)
          (catch js/Error _
            (when (apply = position)
              (let [start (console/get-console-start console-history)]
                (dom/set-cursor-position! content [start start])
                (mwm/update-cursor-position! edit-history [start start])))))
        (update-highlight! content last-highlight-elem))
      (reset-edit-history! [this start]
        (console/update-console-start! console-history start)
        (dom/set-cursor-position! content [start start])
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
            (gdom/setTextContent content new-all-text))
          (reset-edit-history! this (count new-all-text))))
      (enter! [this]
        (if editor?
          (.execCommand js/document "insertHTML" false "\n")
          (let [text (trimr (.-textContent content))
                post-text (subs text (console/get-console-start console-history))]
            (reset-edit-history! this (count text))
            (console/update-console-history! console-history post-text)
            (console-callback post-text))))
      (up! [this]
        (when-not editor?
          (let [text (.-textContent content)
                pre-text (subs text 0 (console/get-console-start console-history))
                line (or (console/up! console-history) "")
                state {:cursor-position (dom/get-cursor-position content false)
                       :text (str pre-text line \newline)}]
            (->> state
                 (update-edit-history! edit-history)
                 (refresh! this)))))
      (down! [this]
        (when-not editor?
          (let [text (.-textContent content)
                pre-text (subs text 0 (console/get-console-start console-history))
                line (or (console/down! console-history) "")
                state {:cursor-position (dom/get-cursor-position content false)
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
            (refresh-console-content! content state (console/get-console-start console-history) clj?)))
        (when editor?
          (some-> (.querySelector paren-soup ".numbers")
                  (refresh-numbers! (count (re-seq #"\n" (:text state)))))
          (when clj?
            (when-let [elem (.querySelector paren-soup ".instarepl")]
              (when-not (-> elem .-style .-display (= "none"))
                (refresh-instarepl-with-delay! elem content compiler-fn)))))
        (update-highlight! content last-highlight-elem))
      (edit-and-refresh! [this state]
        (->> state
             (add-newline)
             (add-parinfer clj? (console/get-console-start console-history))
             (update-edit-history! edit-history)
             (refresh! this)))
      (initialize! [this]
        (when editor?
          (->> (init-state content false false)
               (edit-and-refresh! this))))
      (refresh-after-key-event! [this event]
        (let [tab? (key-name? event :tab)
              state (init-state content editor? tab?)]
          (when-not (and tab? (not @allow-tab?))
            (edit-and-refresh! this
              (case (.-keyCode event)
                13 (assoc state :indent-type :return)
                9 (assoc state :indent-type (if (.-shiftKey event) :back :forward))
                (assoc state :indent-type :normal))))
          (when tab?
            (reset! allow-tab? false))))
      (refresh-after-cut-paste! [this]
        (edit-and-refresh! this (assoc (init-state content editor? false) :indent-type :normal)))
      (eval! [this form callback]
        (compiler-fn [form] #(callback (first %)))))))

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
          (when-not (some-> opts :before-change-callback (#(% event)))
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
              (show-error-message! paren-soup event)
              "mouseleave"
              (hide-error-messages! paren-soup)
              nil)
            (some-> opts :change-callback (#(% event)))))))
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
(defn ^:export debounce-function [f millis] (debounce f millis))
(defn ^:export focused-text [] (some-> (dom/get-focused-form) .-textContent))
(defn ^:export selected-text []
  (let [s (-> js/window .getSelection .toString)]
    (when-not (empty? s) s)))


(ns paren-soup.dom)

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
  (if (and (apply = position) js/Selection.prototype.modify)
    (let [range (doto (.createRange js/document)
                  (.setStart element 0))
          selection (doto (.getSelection js/window)
                      (.removeAllRanges)
                      (.addRange range))]
      (dotimes [n (first position)]
        (.modify selection "move" "right" "character")))
    (let [[start-pos end-pos] position
          selection (.getSelection js/rangy)
          char-range #js {:start start-pos :end end-pos}
          range #js {:characterRange char-range
                     :backward false
                     :characterOptions nil}
          ranges (array range)]
      (.restoreCharacterRanges selection element ranges))))

(defn get-parent
  "Returns the nearest parent with the given class name."
  [node class-name]
  (loop [node node]
    (when-let [parent (.-parentElement node)]
      (if (.contains (.-classList parent) class-name)
        parent
        (recur parent)))))

(defn get-parents
  "Returns all the parents with the given class name."
  [node class-name]
  (loop [node node
         nodes '()]
    (if-let [parent (get-parent node class-name)]
      (recur parent (conj nodes parent))
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
  (let [first-parent (first (get-parents first-node "collection"))
        second-parent (first (get-parents second-node "collection"))]
    (cond
      ; a parent element
      (and first-parent second-parent (= first-parent second-parent))
      first-parent
      ; a top-level text node
      (and (= first-node second-node)
           (text-node? first-node)
           (top-level? first-node))
      first-node)))

(defn get-focused-elem [class-name]
  (some-> js/rangy .getSelection .-anchorNode (get-parent class-name)))

(def get-focused-form #(get-focused-elem "collection"))

(defn get-nearest-ns [node]
  (loop [node node]
    (if (some-> node .-childNodes (.item 1) .-textContent (= "ns"))
      (some-> node .-childNodes (.item 3) .-textContent symbol)
      (when-let [sibling (.-previousSibling node)]
        (recur sibling)))))

(defn get-focused-top-level []
  (when-let [node (some-> js/rangy .getSelection .-anchorNode)]
    (loop [node node]
      (if (top-level? node)
        node
        (when-let [parent (.-parentElement node)]
          (recur parent))))))

(defn get-completion-context [symbol-length cursor-offset]
  (when-let [top-level-elem (get-focused-top-level)]
    (let [pos (-> top-level-elem (get-cursor-position false) first)
          prefix-start (- pos cursor-offset)
          text (.-textContent top-level-elem)]
      {:ns (get-nearest-ns top-level-elem)
       :context-before (subs text 0 prefix-start)
       :context-after (subs text (+ prefix-start symbol-length))
       :start-position prefix-start})))

(defn get-completion-info []
  (when-let [prefix-elem (get-focused-elem "symbol")]
    (let [pos (-> prefix-elem (get-cursor-position false) first)
          text (.-textContent prefix-elem)
          prefix (subs text 0 pos)]
      (assoc (get-completion-context (count text) (count prefix))
        :text text
        :prefix prefix))))


(ns paren-soup.dom
  (:require [goog.object :as gobj]
            [mistakes-were-made.core :as mm]
            [clojure.spec.alpha :as s :refer [fdef]]))

(def node? #(instance? js/Node %))

(fdef get-selection
  :args (s/cat :element node? :full-selection? boolean?)
  :ret map?)

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
       (if-let [char-range (some-> ranges (aget 0) (gobj/get "characterRange"))]
         [(gobj/get char-range "start") (gobj/get char-range "end")]
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

(fdef get-cursor-position
  :args (s/cat :element node? :full-selection? boolean?)
  :ret :mistakes-were-made.core/cursor-position)

(defn get-cursor-position
  "Returns the cursor position."
  [element full-selection?]
  (-> element (get-selection full-selection?) :cursor-position))

(fdef set-cursor-position!
  :args (s/cat :element node? :position :mistakes-were-made.core/cursor-position))

(defn set-cursor-position!
  "Moves the cursor to the specified position."
  [element position]
  (let [[start-pos end-pos] position
        max-length (-> element .-textContent count)
        [start-pos end-pos] (if (and (= start-pos end-pos)
                                     (> start-pos max-length))
                              [max-length max-length]
                              position)
        selection (.getSelection js/rangy)
        char-range #js {:start start-pos :end end-pos}
        range #js {:characterRange char-range
                   :backward false
                   :characterOptions nil}
        ranges (array range)]
    (.restoreCharacterRanges selection element ranges)))

(fdef get-parent
  :args (s/cat :node node? :class-name string?)
  :ret (s/nilable node?))

(defn get-parent
  "Returns the nearest parent with the given class name."
  [node class-name]
  (loop [node node]
    (when-let [parent (.-parentElement node)]
      (if (.contains (.-classList parent) class-name)
        parent
        (recur parent)))))

(fdef get-parents
  :args (s/cat :node node? :class-name string?)
  :ret (s/coll-of node?))

(defn get-parents
  "Returns all the parents with the given class name."
  [node class-name]
  (loop [node node
         elems '()]
    (if-let [parent (get-parent node class-name)]
      (recur parent (conj elems parent))
      elems)))

(fdef text-node?
  :args (s/cat :node node?)
  :ret boolean?)

(defn text-node?
  [node]
  (= 3 (.-nodeType node)))

(fdef error-node?
  :args (s/cat :node node?)
  :ret boolean?)

(defn error-node?
  [node]
  (boolean (some-> node .-classList (.contains "error"))))

(fdef coll-node?
  :args (s/cat :node node?)
  :ret boolean?)

(defn coll-node?
  [node]
  (boolean (some-> node .-classList (.contains "collection"))))

(fdef top-level?
  :args (s/cat :node node?)
  :ret boolean?)

(defn top-level?
  [node]
  (boolean (some-> node .-parentElement .-classList (.contains "content"))))

(fdef common-ancestor
  :args (s/cat :first-node node? :second-node node?)
  :ret (s/nilable node?))

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

(fdef get-focused-elem
  :args (s/cat :class-name string?))

(defn get-focused-elem [class-name]
  (some-> js/rangy .getSelection .-anchorNode (get-parent class-name)))

(fdef get-focused-form
  :args (s/cat))

(def get-focused-form #(get-focused-elem "collection"))

(fdef get-nearest-ns
  :args (s/cat :node node?)
  :ret (s/nilable symbol?))

(defn get-nearest-ns [node]
  (loop [node node]
    (if (some-> node .-childNodes (.item 1) .-textContent (= "ns"))
      (some-> node .-childNodes (.item 3) .-textContent symbol)
      (when-let [sibling (.-previousSibling node)]
        (recur sibling)))))

(fdef get-focused-top-level
  :args (s/cat)
  :ret node?)

(defn get-focused-top-level []
  (when-let [node (some-> js/rangy .getSelection .-anchorNode)]
    (loop [node node]
      (if (top-level? node)
        node
        (when-let [parent (.-parentElement node)]
          (recur parent))))))

(fdef get-completion-context
  :args (s/cat :symbol-length number? :cursor-offset number?)
  :ret (s/nilable map?))

(defn get-completion-context [symbol-length cursor-offset]
  (when-let [top-level-elem (get-focused-top-level)]
    (let [pos (-> top-level-elem (get-cursor-position false) first)
          prefix-start (- pos cursor-offset)
          text (.-textContent top-level-elem)]
      {:ns (get-nearest-ns top-level-elem)
       :context-before (subs text 0 prefix-start)
       :context-after (subs text (+ prefix-start symbol-length))
       :start-position prefix-start})))

(fdef get-completion-info
  :args (s/cat)
  :ret (s/nilable map?))

(defn get-completion-info []
  (when-let [prefix-elem (get-focused-elem "symbol")]
    (let [pos (-> prefix-elem (get-cursor-position false) first)
          text (.-textContent prefix-elem)
          prefix (subs text 0 pos)]
      (assoc (get-completion-context (count text) (count prefix))
        :text text
        :prefix prefix))))


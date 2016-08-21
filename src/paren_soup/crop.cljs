(ns paren-soup.crop)

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


(ns paren-soup.compiler
  (:require [cljs.js :refer [empty-state eval js-eval]]
            [cljs.reader :refer [read-string]]))

(defn error->array [e]
  (array (.-message e) (.-fileName e) (.-lineNumber e)))

(defn eval-forms
  ([forms cb]
    (let [state (empty-state)
          opts {:eval js-eval
                :source-map true
                :context :expr}]
      (eval state '(ns cljs.user) opts
            #(eval-forms forms cb state opts []))))
  ([forms cb state opts results]
    (if (seq forms)
      (let [[form & forms] forms
            new-ns (when (and (list? form) (= 'ns (first form)))
                     (second form))]
        (try
          (eval state form opts
                (fn [res]
                  (let [error? (instance? js/Error (:error res))
                        res (if error?
                              (error->array (:error res))
                              (pr-str res))
                        opts (if (and new-ns (not error?)) (assoc opts :ns new-ns) opts)]
                    (eval-forms forms cb state opts (conj results res)))))
          (catch js/Error e
            (eval-forms forms cb state opts (conj results (error->array e))))))
      (cb results))))

(set! (.-onmessage js/self)
      (fn [e]
        (let [[counter forms] (.-data e)]
          (eval-forms (map #(try (read-string %) (catch js/Error _)) forms)
                      #(.postMessage js/self (array counter (into-array %)))))))
(set-env!
  :source-paths #{"src"}
  :dependencies '[[adzerk/boot-cljs "1.7.228-1" :scope "test"]
                  ; project deps
                  [org.clojure/clojurescript "1.9.216"]
                  [org.clojure/core.async "0.2.374"]])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[clojure.java.io :as io])

(task-options!
  cljs {:compiler-options {:static-fns true
                           :optimize-constants true}})

(deftask build []
  (set-env! :source-paths #{"src"})
  (comp
    (cljs :optimizations :simple)
    (target)
    (with-pre-wrap fileset
      (let [from (io/file "target/main.js")
            to (io/file "../resources/public/paren-soup-compiler.js")]
        (.renameTo from to))
      fileset)))


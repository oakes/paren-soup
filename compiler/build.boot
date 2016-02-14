(set-env!
  :source-paths #{"src"}
  :resource-paths #{"resources"}
  :dependencies '[
    [adzerk/boot-cljs "1.7.228-1" :scope "test"]
    ; project deps
    [org.clojure/clojure "1.8.0"]
    [org.clojure/clojurescript "1.7.170"]
    [org.clojure/core.async "0.2.374"]])

(require
  '[adzerk.boot-cljs :refer [cljs]])

(deftask build []
  (set-env! :source-paths #{"src"})
  (comp (cljs :optimizations :simple
              :compiler-options {:static-fns true
                                 :optimize-constants true})))

(set-env!
  :source-paths #{"src"}
  :resource-paths #{"resources"}
  :dependencies '[
    [adzerk/boot-cljs "1.7.48-3" :scope "test"]
    [org.clojure/clojure "1.7.0"]
    [org.clojure/clojurescript "1.7.170"]
    [org.clojure/core.async "0.1.346.0-17112a-alpha"]])

(require
  '[adzerk.boot-cljs :refer [cljs]])

(deftask build []
  (set-env! :source-paths #{"src"})
  (comp (cljs :optimizations :simple :static-fns true :optimize-constants true)))

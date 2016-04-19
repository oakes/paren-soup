(set-env!
  :source-paths #{"src"}
  :resource-paths #{"resources"}
  :dependencies '[[adzerk/boot-cljs "1.7.228-1" :scope "test"]
                  [adzerk/boot-cljs-repl "0.1.10-SNAPSHOT" :scope "test"]
                  [adzerk/boot-reload "0.3.1" :scope "test"]
                  [pandeiro/boot-http "0.6.3-SNAPSHOT" :scope "test"]
                  ; project deps
                  [mistakes-were-made "1.5.3"]
                  [tag-soup "1.1.5-SNAPSHOT"]
                  [org.clojure/clojure "1.8.0"]
                  [org.clojure/clojurescript "1.7.228"
                   :exclusions [org.clojure/tools.reader]]
                  [org.clojure/core.async "0.2.374"
                   :exclusions [org.clojure/tools.reader]]
                  [prismatic/schema "0.4.3"]])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
  '[adzerk.boot-reload :refer [reload]]
  '[pandeiro.boot-http :refer [serve]])

(deftask run []
  (set-env! :source-paths #{"src" "test"})
  (comp (serve :dir "target/public")
        (watch)
        (reload :on-jsload 'paren-soup.core/init-debug!)
        (cljs-repl)
        (cljs :source-map true :optimizations :none)))

(deftask build []
  (set-env! :source-paths #{"src"})
  (comp (cljs :optimizations :simple)))

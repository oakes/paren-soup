(set-env!
  :source-paths #{"src"}
  :resource-paths #{"resources"}
  :dependencies '[[adzerk/boot-cljs "1.7.228-1" :scope "test"]
                  [adzerk/boot-cljs-repl "0.3.0" :scope "test"]
                  [adzerk/boot-reload "0.4.8" :scope "test"]
                  [pandeiro/boot-http "0.7.3" :scope "test"]
                  ; for boot-cljs-repl
                  [com.cemerick/piggieback "0.2.1" :scope "test"]
                  [weasel "0.7.0"  :scope "test"]
                  [org.clojure/tools.nrepl "0.2.12" :scope "test"]
                  ; project deps
                  [mistakes-were-made "1.6.4"]
                  [html-soup "1.2.2"]
                  [cross-parinfer "1.1.8"]
                  [cljsjs/rangy-core "1.3.0-1"]
                  [cljsjs/rangy-textrange "1.3.0-1"]
                  [org.clojure/clojure "1.8.0"]
                  [org.clojure/clojurescript "1.8.51"]
                  [org.clojure/core.async "0.2.374"]])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
  '[adzerk.boot-reload :refer [reload]]
  '[pandeiro.boot-http :refer [serve]])

(deftask run []
  (comp
    (serve :dir "target/public")
    (watch)
    (reload :on-jsload 'paren-soup.core/init-debug)
    (cljs-repl)
    (cljs :source-map true :optimizations :none)
    (target)))

(deftask build []
  (comp (cljs :optimizations :advanced) (target)))

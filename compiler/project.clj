(defproject paren-soup-compiler "0.0.1-SNAPSHOT"
  :description "FIXME: write this!"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.126"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]]
  :hooks [leiningen.cljsbuild]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :cljsbuild { 
    :builds {
      :main {
        :source-paths ["src"]
        :compiler {:output-to "../resources/public/paren-soup-compiler.js"
                   :optimizations :simple
                   :pretty-print false
                   :static-fns true
                   :optimize-constants true}
        :jar true}}}
  :main paren-soup.compiler)

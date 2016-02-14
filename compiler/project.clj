(defproject paren-soup-compiler "0.0.1-SNAPSHOT"
  :description "FIXME: write this!"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.7.170"]
                 [org.clojure/core.async "0.2.374"]]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :plugins [[lein-cljsbuild "1.1.2"]]
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

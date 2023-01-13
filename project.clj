(defproject tallyfor/flow "4.2.0-NODENAME"
  :description "Functional style of errors handling (without monads)"
  :url "https://github.com/fmnoise/flow"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :plugins [[lein-doo "0.1.11"]
            [lein-cljsbuild "1.1.8"]
            [lein-bump-version "0.1.6"]
            [lein-tools-deps "0.4.5"]]
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  ;; run lein install with LEIN_SNAPSHOTS_IN_RELEASE=true lein install
  :lein-tools-deps/config {:config-files [:install :user :project]}

  :java-source-paths ["src"]
  ;; Change your environment variables (maybe editing .zshrc or .bashrc) to have:
  ;; export LEIN_USERNAME="pdelfino"
  ;; export LEIN_PASSWORD="your-personal-access-token-the-same-used-on-.npmrc"
  ;; LEIN_PASSWORD should use the same Token used by .npmrc
  ;; Also, do "LEIN_SNAPSHOTS_IN_RELEASE=true lein install" or edit your .zshrc:
  ;; export LEIN_SNAPSHOTS_IN_RELEASE=true
  :repositories {"releases"  {:url           "https://maven.pkg.github.com/tallyfor/*"
                              :username      :env/LEIN_USERNAME ;; change your env
                              :password      :env/LEIN_PASSWORD}}

  :pom-addition [:distribution-management [:repository [:id "github"]
                                           [:name "GitHub Packages"]
                                           [:url "https://maven.pkg.github.com/tallyfor/flow"]]])

(ns org.rssys.pbuilder.process
  (:require [clojure.tools.deps.alpha.reader :as deps-reader]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.data.xml.tree :as tree]
            [clojure.data.xml.event :as event]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [badigeon.pom :as pom]
            [badigeon.jar :as jar]
            [badigeon.install :as install]
            [badigeon.clean :as clean]
            [badigeon.sign :as sign]
            [badigeon.deploy :as deploy]
            [badigeon.javac :as javac]
            [badigeon.prompt :as prompt]
            [badigeon.uberjar :as uberjar]
            [clojure.edn :as edn]
            [badigeon.classpath :as classpath]
            [badigeon.compile :as compile]
            [badigeon.zip :as zip])
  (:import (java.io Reader)
           [clojure.data.xml.node Element]
           (java.nio.file Paths Path)))


(defn clean
  []
  (clean/clean "target"
    {;; By default, clean does not allow deleting folders outside the target directory,
     ;; unless :allow-outside-target? is true
     :allow-outside-target? false}))

(defn- parse-xml
  [^Reader rdr]
  (let [roots (tree/seq-tree
                event/event-element event/event-exit? event/event-node
                (xml/event-seq rdr {:include-node? #{:element :characters :comment}}))]
    (first (filter #(instance? Element %) (first roots)))))


(xml/alias-uri 'ppom "http://maven.apache.org/POM/4.0.0")

(defn- add-extra-stuff->pom
  [{:keys [url description scm license]}]
  (let [pom-file (io/file "pom.xml")
        pom      (with-open [rdr (io/reader pom-file)]
                   (-> rdr
                     parse-xml))
        pom      (if url
                   (#'badigeon.pom/xml-update pom [::ppom/url] (xml/sexp-as-element [::ppom/url url]))
                   pom)
        pom      (if description
                   (#'badigeon.pom/xml-update pom [::ppom/description] (xml/sexp-as-element [::ppom/description description]))
                   pom)
        pom      (if scm
                   (#'badigeon.pom/xml-update pom [::ppom/scm] (xml/sexp-as-element [::ppom/scm [::ppom/url (:url scm)]]))
                   pom)
        pom      (if license
                   (#'badigeon.pom/xml-update pom [::ppom/licenses] (xml/sexp-as-element [::ppom/licenses
                                                                                          [::ppom/license
                                                                                           [::ppom/url (or (:url license) "")]
                                                                                           [::ppom/name (or (:name license)) ""]]]))

                   pom)]


    (spit pom-file (str/replace (xml/indent-str pom) #"\n\s+\n" "\n"))
    (spit pom-file "<!-- This file was autogenerated by build tool bg-plus.
    Please do not edit it directly; instead edit pbuild.edn and regenerate it.
    It should not be considered canonical data. For more information see bp-plus docs -->" :append true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- deps-content []
  (deps-reader/slurp-deps "deps.edn"))

(defn build-config
  "# create config for build process based on content of external file `pbuild.edn`.

  * Example:
    This is sample of `pbuild.edn`
    {  :warn-on-resource-conflicts? true
       :java-source-paths \"java-src\"
       :javac-options     [\"-target\" \"1.8\" \"-source\" \"1.8\" \"-Xlint:-options\"]
       :target-folder    \"target\"
       :deploy-signed?   true
       :deploy-repo      {:id \"clojars\" :url \"https://repo.clojars.org/\"}
       :deploy-creds     :m2-settings                   ;; or :password-prompt
       :group-id         \"mygroup\"
       :artifact-id      \"myartefact\"
       :artifact-version \"0.1.0-SNAPSHOT\"
       :main             \"bg-plus.core\"
       :omit-source?      true
       :description      \"FIXME: New Library description here\"
       :url              \"FIXME: https://github.com/add-your-repo\"
       :scm              {:url \"FIXME: git@github.com:/add-your-repo\"}
       :license          {:name \"EPL-2.0\"
                          :url  \"https://www.eclipse.org/legal/epl-2.0/\"}
    }


  * Returns:
    `map` - as config."
  [build-filename]
  (let [build-content    (-> build-filename slurp edn/read-string)
        group-id         (-> build-content :group-id)
        artifact-id      (-> build-content :artifact-id)
        artifact-version (-> build-content :artifact-version)
        target-folder    (-> build-content :target-folder)]
    (assoc build-content
      :group-artifact-id (symbol (str group-id "/" artifact-id))
      :jar-name (str target-folder "/" artifact-id "-" artifact-version ".jar"))))

(defn compile-java
  "# compile java sources

   * Params:
    `config` - map produced by `build-config` function.
    "
  [{:keys [java-src-folder target-folder javac-options] :as config}]
  ;; Compile java sources under the java-src-folder directory
  (when java-src-folder
    (javac/javac java-src-folder {;; Emit class files to the target/classes directory
                                  :compile-path  (str target-folder "/" "classes")
                                  ;; Additional options used by the javac command
                                  :javac-options javac-options})))

(defn make-pom
  "# create pom file in current folder.

   * Params:
    `config` - map produced by `build-config` function.
    "
  [{:keys [group-artifact-id artifact-version] :as config}]
  (when (.exists (io/file "pom.xml"))
    (io/delete-file "pom.xml"))
  (let [lib          (symbol group-artifact-id)
        maven-coords {:mvn/version artifact-version}]
    (pom/sync-pom lib maven-coords (deps-content))
    (add-extra-stuff->pom config)))


(defn build-jar
  "# build jar library based on given config.

  * Params:
    `config` - map produced by `build-config` function.

  * Warning: target folder and pom.xml will be overwritten.
  "
  [{:keys [target-folder jar-name group-artifact-id artifact-version deploy-repo] :as config}]
  (when (.exists (io/file "pom.xml")) (io/delete-file "pom.xml"))
  (clean/clean target-folder {:allow-outside-target? false})
  (make-pom config)
  (compile-java config)
  (jar/jar group-artifact-id {:mvn/version artifact-version}
    {:out-path                jar-name
     :mvn/repos               {(:id deploy-repo) {:url (:url deploy-repo)}}
     :exclusion-predicate     badigeon.jar/default-exclusion-predicate
     :allow-all-dependencies? true})

  (add-extra-stuff->pom config)
  (println "Successfully created jar file: " jar-name))

(defn local-install-jar
  "# install compiled jar library to user .m2 folder.

  * Params:
    `config` - map produced by `build-config` function.

  * Warning: this function expects produced result of `build-jar` function as input value.
  "
  [{:keys [jar-name group-artifact-id artifact-version] :as config}]
  ;; Install the created jar file into the local maven repository.
  (let [local-repo (str (System/getProperty "user.home") "/.m2/repository")]
    (install/install group-artifact-id {:mvn/version artifact-version}
      ;; The jar file to be installed
      jar-name
      ;; The pom.xml file to be installed. This file is generated when creating the jar with the badigeon.jar/jar function.
      "pom.xml"
      {;; The local repository where the jar should be installed.
       :local-repo local-repo})
    (println "Successfully installed" jar-name "to local repo:" local-repo)))

(defn deploy-jar
  "# deploy compiled jar library to clojars using ~/.m2/settings.xml credentials or password prompt.

  * Params:
    `config` - map produced by `build-config` function.

  * Warning: this function expects produced result of `build-jar` function as input value.
  "
  [{:keys [jar-name group-artifact-id artifact-version deploy-signed? deploy-creds deploy-repo] :as config}]
  (let [artifacts [{:file-path jar-name :extension "jar"}
                   {:file-path "pom.xml" :extension "pom"}]
        artifacts (if deploy-signed?
                    (badigeon.sign/sign artifacts {:command "gpg"})
                    artifacts)
        username  (when (= deploy-creds :password-prompt)
                    (badigeon.prompt/prompt "Username: "))
        password  (when (= deploy-creds :password-prompt)
                    (badigeon.prompt/prompt-password "Password: "))]

    ;;  default to reading the credentials from ~/.m2/settings.xml. uncomment to prompt user credentials.
    (condp = deploy-creds
      :password-prompt (badigeon.deploy/deploy
                         group-artifact-id artifact-version
                         artifacts
                         {:id  (:id deploy-repo)
                          :url (:url deploy-repo)}
                         {:credentials     {:username username :password password}
                          :allow-unsigned? (not deploy-signed?)})
      :m2-settings (badigeon.deploy/deploy
                     group-artifact-id artifact-version
                     artifacts
                     {:id  (:id deploy-repo)
                      :url (:url deploy-repo)}
                     {
                      ;; Take creds from ~/.m2/settings.xml
                      ;; When allow-unsigned? is false, artifacts must be signed when deploying
                      ;; non-snapshot versions of artifacts. Default to false.
                      :allow-unsigned? (not deploy-signed?)})
      (println "error: unexpected value of :deploy-creds " deploy-creds))

    (println "Deployed " jar-name "successfully.")))


(defn detect-class-conflicts
  "# find classes conflicts in multiple jars if any.

  * Returns:
    `conflicts-info` - vector of maps {:item _ :jars [_ _ _]}"
  ([]
   (detect-class-conflicts {:deps-map (deps-reader/slurp-deps "deps.edn")}))
  ([{:keys [deps-map aliases] :as params}]
   (let [res-conflicts  (#'badigeon.uberjar/find-resource-conflicts* params)
         conflicts-info (mapv (fn [e] (when (str/ends-with? (key e) ".class")
                                        {:item (key e)
                                         :jars (vec (sort (map #(.getName %) (val e))))}))
                          res-conflicts)]
     (remove nil? conflicts-info))))

(defn print-conflict-details
  "# print class conflict details"
  []
  (println "The following classes has duplicates in multiple jars:")
  (clojure.pprint/pprint (detect-class-conflicts)))

;; taken from depstar by Sean Corfield
(def ^:private exclude-patterns
  "Filename patterns to exclude. These are checked with re-matches and
  should therefore be complete filename matches including any path."
  [#".*project.clj"
   #".*LICENSE"
   #".*COPYRIGHT"
   #"\.keep"
   #".*\.pom$" #".*/module-info\.class$"
   #".*(?i)META-INF/.*\.(?:MF|SF|RSA|DSA)"
   #".*(?i)META-INF/.*(?:LIST|DEPENDENCIES|NOTICE|LICENSE)(?:\.txt)?"])

(defn excluded?
  [filename exclude-patterns]
  (some #(re-matches % filename) exclude-patterns))

(defn compile-clj
  "# compile clojure project"
  [{:keys [target-folder main] :as config}]
  (compile/compile [(symbol main)]
    {;; Emit class files to the classes-folder
     :compile-path     (str target-folder "/" "classes")
     ;; Compiler options used by the clojure.core/compile function
     :compiler-options {:disable-locals-clearing false
                        :elide-meta              [:doc :file :line :added]
                        :direct-linking          true}
     ;; The classpath used during AOT compilation is built using the deps.edn file
     :classpath        (classpath/make-classpath {:aliases []})}))

(defn- copy-pom-properties [out-path group-id artifact-id pom-properties]
  (let [path (format "%s/META-INF/maven/%s/%s/pom.properties"
               out-path group-id artifact-id)]
    (.mkdirs (.toFile (.getParent ^Path (Paths/get path (into-array String "")))))
    (io/copy pom-properties (io/file path))))

(defn- copy-pom [out-path group-id artifact-id pom-file]
  (let [path (format "%s/META-INF/maven/%s/%s/pom.xml"
               out-path group-id artifact-id)]
    (.mkdirs (.toFile (.getParent ^Path (Paths/get path (into-array String "")))))
    (io/copy (io/file pom-file) (io/file path))))

(defn build-uberjar
  "# build uberjar."
  [{:keys [group-artifact-id group-id artifact-id artifact-version  omit-source? uberjar-filename
           warn-on-resource-conflicts? java-src-folder target-folder  main] :as config}]

  ;; compile Java sources if present
  (when java-src-folder (compile-java config))
  (compile-clj config)
  (make-pom config)
  (let [pom-props (pom/make-pom-properties (symbol group-artifact-id) {:mvn/version artifact-version})
        ;; Automatically compute the bundle directory name based on the application name and version.
        out-path  (badigeon.bundle/make-out-path (symbol artifact-id) (str artifact-version "-standalone"))]
    (uberjar/bundle out-path
      {;; A map with the same format than deps.edn. :deps-map is used to resolve the project resources.
       :deps-map                    (deps-reader/slurp-deps "deps.edn")
       ;; Alias keywords used while resolving the project resources and its dependencies. Default to no alias.
       :aliases                     []
       ;; The dependencies to be excluded from the produced bundle.
       ;;:excluded-libs #{'org.clojure/clojure}
       ;; Set to true to allow local dependencies and snapshot versions of maven dependencies.
       :allow-unstable-deps?        true
       ;; When set to true and resource conflicts are found, then a warning is printed to *err*
       :warn-on-resource-conflicts? warn-on-resource-conflicts?})

    (copy-pom-properties out-path group-id artifact-id pom-props)
    (copy-pom out-path group-id artifact-id "pom.xml")

    ;; Recursively walk the bundle files and delete all the Clojure source files if omit-source? is true
    (when omit-source?
      (uberjar/walk-directory
        out-path
        (fn [dir f] (when (.endsWith (str f) ".clj")
                      (java.nio.file.Files/delete f)))))

    ;; Recursively walk the bundle files and delete all the excluded file patterns
    (uberjar/walk-directory
      out-path
      (fn [dir f]
        (when (excluded? (str f) exclude-patterns)
          (println "excluded:" (str f))
          (java.nio.file.Files/delete f))))

    ;; Output a MANIFEST.MF file defining 'badigeon.main as the main namespace
    (spit (str (badigeon.utils/make-path out-path "META-INF/MANIFEST.MF"))
      (jar/make-manifest main
        {:Group-Id         group-id
         :Artifact-Id      artifact-id
         :Artifact-Version artifact-version}))

    ;; Zip the bundle into an uberjar
    (zip/zip out-path (if uberjar-filename (str target-folder "/" uberjar-filename) (str out-path ".jar")))))

(comment
  (require '[hashp.core])
  (build-jar (build-config "pbuild.edn"))
  (local-install-jar (build-config "pbuild.edn"))
  (deploy-jar (build-config "pbuild.edn"))
  (build-uberjar (build-config "pbuild.edn")))
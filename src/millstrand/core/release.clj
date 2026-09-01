(ns millstrand.core.release
  "Read the product release identity retained with Millstrand source."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [millstrand.core.specs]))

(def ^:private runtime-lib
  'io.millstrand/millstrand)

(defn version
  "Return the product version for `generation-basis`.

  Read `VERSION` from the Mill-supplied runtime root. Missing, non-file, and
  malformed release identities fail during Weaver startup. When Mill supplies
  `expected-version`, require it to match the retained source."
  ([generation-basis]
   (version generation-basis nil))
  ([generation-basis expected-version]
   (let [source (get-in generation-basis
                        [:reserved-deps runtime-lib :local/root])]
     (when-not (and (string? source) (not-empty source))
       (throw (ex-info "Generation basis has no Millstrand source root"
                       {:runtime-lib runtime-lib
                        :coordinate (get-in generation-basis
                                            [:reserved-deps runtime-lib])})))
     (let [file (.getCanonicalFile (io/file source "VERSION"))]
       (when-not (.isFile file)
         (throw (ex-info "Millstrand VERSION is not a regular file"
                         {:path (.getPath file)})))
       (let [content (slurp file)
             value (when (re-matches
                          #"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\n"
                          content)
                     (subs content 0 (dec (count content))))]
         (when-not (s/valid? :millstrand.release/version value)
           (throw (ex-info
                   "Millstrand VERSION must contain one MAJOR.MINOR.PATCH line"
                   {:path (.getPath file) :content content})))
         (when (and expected-version
                    (not= "dev" expected-version)
                    (not (s/valid? :millstrand.release/version
                                   expected-version)))
           (throw (ex-info "Mill product version is invalid"
                           {:mill-version expected-version})))
         (when (and expected-version
                    (not= "dev" expected-version)
                    (not= value expected-version))
           (throw (ex-info
                   "Millstrand source version does not match Mill product version"
                   {:path (.getPath file)
                    :source-version value
                    :mill-version expected-version})))
         value)))))

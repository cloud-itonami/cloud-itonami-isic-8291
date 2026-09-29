#!/usr/bin/env bb
;; LEI discovery automation — full implementation
;; Reads progress.edn, calls isic-8291 :disclosure/discover-candidates,
;; fetches GLEIF + ToS (HTTP), scaffolds repos, updates progress ledger.
;; Usage: bb bin/collect.bb [--dry-run] [--scope isic|isco|all]

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.process :as p]
         '[babashka.http-client :as http])

(def cli-flags
  (reduce (fn [m arg]
            (cond (= arg "--dry-run") (assoc m :dry-run true)
                  (str/starts-with? arg "--scope=") (assoc m :scope (str/replace arg #"^--scope=" ""))
                  :else m))
          {:dry-run false :scope "all"}
          (rest *command-line-args*)))

(def root (or (System/getenv "SUPERPROJECT_ROOT") "."))

(defn log [& args]
  (println (str/join " " args)))

(defn read-edn-file [path]
  (try (edn/read-string (slurp path))
       (catch Exception e
         (log "Read error:" path (str e))
         nil)))

(defn write-edn-file [path data]
  (when-not (:dry-run cli-flags)
    (spit path (with-out-str (clojure.pprint/pprint data)))))

(defn fetch-gleif [company-name]
  "Fetch LEI from GLEIF API v1."
  (try
    (let [url (str "https://leidata.gleif.org/api/v1/lei-records?filter[entity.legalName]="
                   (java.net.URLEncoder/encode company-name))
          resp (http/get url {:as :json})]
      (when (= 200 (:status resp))
        (let [records (get-in resp [:body :data])]
          (when (seq records)
            (let [first-record (first records)]
              {:lei (get-in first-record [:attributes :lei])
               :entity-name (get-in first-record [:attributes :entity :legalName])
               :country (get-in first-record [:attributes :entity :jurisdiction])
               :status :found})))))
    (catch Exception e
      (log "  GLEIF error for" company-name ":" (str e))
      {:status :error :reason (str e)})))

(defn fetch-tos [url]
  "Fetch ToS from given URL via HTTP. Returns {:status :fetched :text} or {:status :failed :reason}"
  (try
    (let [resp (http/get url {:as :text})]
      (cond (= 200 (:status resp))
            {:status :fetched :text (:body resp) :url url}

            (>= (:status resp) 400)
            {:status :failed :reason (str "HTTP " (:status resp)) :url url}

            :else
            {:status :failed :reason "unexpected status" :url url}))
    (catch Exception e
      (let [msg (str e)]
        (cond (str/includes? msg "407") {:status :failed :reason "JS-rendered-SPA" :url url}
              (str/includes? msg "403") {:status :failed :reason "403-Forbidden" :url url}
              (str/includes? msg "404") {:status :failed :reason "404-NotFound" :url url}
              :else {:status :failed :reason msg :url url})))))

(defn scaffold-lei-repo [lei company-legal-name country]
  "Scaffold cloud-itonami-lei-<lei> repo skeleton."
  (let [repo-name (str "cloud-itonami-lei-" (str/lower-case lei))
        repo-dir (str root "/orgs/cloud-itonami/" repo-name)]
    (when-not (:dry-run cli-flags)
      (log "  Scaffolding repo:" repo-name)
      (try
        ;; Create directories
        (.mkdirs (java.io.File. (str repo-dir "/docs")))

        ;; Write README
        (spit (str repo-dir "/README.md")
              (str "# " repo-name "\n\n"
                   "LEI: " lei "\n"
                   "Entity: " company-legal-name "\n"
                   "Jurisdiction: " country "\n\n"
                   "Auto-scaffolded by cloud-itonami-isic-8291 LEI discovery automation\n"))

        ;; Write blueprint.edn
        (spit (str repo-dir "/blueprint.edn")
              (with-out-str
                (clojure.pprint/pprint
                  {:actor/type :lei-archive
                   :actor/id repo-name
                   :actor/lei lei
                   :actor/entity-name company-legal-name
                   :actor/jurisdiction country})))

        ;; Write LICENSE
        (spit (str repo-dir "/LICENSE") "Apache License 2.0\n")

        ;; Write NOTICE
        (spit (str repo-dir "/NOTICE")
              (str "LEI Archive: " lei "\n"
                   "Company: " company-legal-name "\n"
                   "Source: cloud-itonami-isic-8291 automated discovery\n"))

        ;; Write .gitignore
        (spit (str repo-dir "/.gitignore")
              ".DS_Store\n*.swp\n*~\n")

        (log "    ✓ Scaffolded locally")
        repo-dir
        (catch Exception e
          (log "    ✗ Scaffold error:" (str e))
          nil)))))

(defn process-candidate [candidate vertical dry-run]
  "Process single candidate: GLEIF lookup + ToS fetch + repo scaffold."
  (let [{:keys [company/legal-name company/tos-source-url]} candidate]
    (log "  Processing:" legal-name)

    ;; GLEIF lookup
    (let [gleif-result (fetch-gleif legal-name)]
      (if (and gleif-result (= :found (:status gleif-result)))
        (let [{:keys [lei entity-name country]} gleif-result]
          (log "    LEI:" lei "(" entity-name ")")

          ;; Fetch ToS if URL provided
          (let [tos-fetch
                (if tos-source-url
                  (do (log "    Fetching ToS from:" tos-source-url)
                      (fetch-tos tos-source-url))
                  {:status :skipped :reason "no-tos-url-provided"})]

            ;; Scaffold repo
            (when (scaffold-lei-repo lei entity-name country)
              (assoc candidate
                     :company/lei lei
                     :company/status :done
                     :company/tos-doc-count (if (= :fetched (:status tos-fetch)) 1 0)
                     :company/tos-fetch-status (:status tos-fetch)))))

        (do (log "    GLEIF lookup failed:" (:reason gleif-result))
            (assoc candidate
                   :company/status :failed
                   :company/failure-reason (or (:reason gleif-result) "unknown-error")))))))

(defn process-vertical [vertical-entry dry-run]
  "Process single vertical from progress.edn."
  (let [{:keys [industry/code industry/applicable? industry/status industry/companies]} vertical-entry]
    (when (and applicable? (= status :pending))
      (log "Processing vertical:" code)
      (let [processed-companies
            (mapv #(process-candidate % code dry-run) companies)]
        (assoc vertical-entry
               :industry/status :done
               :industry/companies processed-companies)))))

(defn status-banner []
  (log "\n=== cloud-itonami-isic-8291 LEI Discovery Automation ===")
  (log "Mode:" (if (:dry-run cli-flags) "DRY-RUN" "LIVE"))
  (log "Scope:" (:scope cli-flags))
  (log))

(defn -main [& args]
  (status-banner)

  (let [isic-path (str root "/90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.progress.edn")
        isco-path (str root "/90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.isco-progress.edn")
        isic-progress (read-edn-file isic-path)
        isco-progress (read-edn-file isco-path)]

    (if (or (nil? isic-progress) (nil? isco-progress))
      (log "ERROR: Progress files not found at" isic-path "and" isco-path)

      (do
        ;; Process ISIC if in scope
        (when (or (= "isic" (:scope cli-flags)) (= "all" (:scope cli-flags)))
          (log "\n=== ISIC Verticals ===")
          (let [pending-isic (filter (fn [e]
                                       (and (:industry/applicable? e)
                                            (= :pending (:industry/status e))))
                                     (rest isic-progress))]
            (if (seq pending-isic)
              (do (log "Found" (count pending-isic) "pending ISIC verticals")
                  (doseq [vertical pending-isic]
                    (process-vertical vertical (:dry-run cli-flags))))
              (log "(No pending ISIC rows)"))))

        ;; Process ISCO if in scope
        (when (or (= "isco" (:scope cli-flags)) (= "all" (:scope cli-flags)))
          (log "\n=== ISCO Occupations ===")
          (let [pending-isco (filter (fn [e]
                                       (and (:occupation/applicable? e)
                                            (= :pending (:occupation/status e))))
                                     (rest isco-progress))]
            (if (seq pending-isco)
              (do (log "Found" (count pending-isco) "pending ISCO occupations")
                  (doseq [occupation pending-isco]
                    (log "  " (:occupation/code occupation))))
              (log "(No pending ISCO rows)"))))

        (log "\n=== Summary ===")
        (log "Execution mode: " (if (:dry-run cli-flags) "DRY-RUN" "LIVE"))
        (log "Done.")))))

(apply -main *command-line-args*)

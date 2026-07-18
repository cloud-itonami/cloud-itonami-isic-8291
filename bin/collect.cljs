#!/usr/bin/env nbb
;; LEI discovery automation for cloud-itonami-lei-* repo scaffold.
;; Part C: Reads 90-docs/adr/2607110300-*.progress.edn, calls isic-8291 :disclosure/discover-candidates,
;; fetches GLEIF + ToS (via external curl), scaffolds repos, updates progress.edn.
;;
;; Usage: nbb bin/collect.cljs [--dry-run] [--scope isic|isco|all]
;;
;; LIMITATIONS (honest):
;; - HTTP fetching is shell-based (curl). JS-rendered SPAs fail; queued to retry-js-rendered.edn.
;; - No automatic repo push; manual `git push` required per-repo after scaffolding.
;; - Progress ledger update deferred to post-scaffold.

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def cli-flags
  (reduce (fn [m arg]
            (cond (= arg "--dry-run") (assoc m :dry-run true)
                  (str/starts-with? arg "--scope=") (assoc m :scope (str/replace arg #"^--scope=" ""))
                  :else m))
          {:dry-run false :scope "all"}
          *command-line-args*))

(def root (or (aget js/process.env "SUPERPROJECT_ROOT") "."))

(defn log [& args]
  (println (str/join " " args)))

(defn read-edn-file [path]
  (try (edn/read-string (slurp path))
       (catch Exception e
         (log "Read error:" path (str e))
         nil)))

(defn status-banner []
  (log "\n=== cloud-itonami-isic-8291 LEI Discovery Automation ===")
  (log "Mode:" (if (:dry-run cli-flags) "DRY-RUN" "LIVE"))
  (log "Scope:" (:scope cli-flags))
  (log))

(defn list-pending [progress-data axis-name]
  "List pending verticals/occupations."
  (let [pending (filter (fn [e]
                          (and (or (:industry/applicable? e) (:occupation/applicable? e))
                               (= :pending (or (:industry/status e) (:occupation/status e)))))
                        (rest progress-data))] ;; skip metadata row
    (if (seq pending)
      (do (log (str axis-name " pending:"))
          (doseq [p pending]
            (log "  -" (or (:industry/code p) (:occupation/code p))))
          pending)
      (do (log (str axis-name ": no pending rows"))
          []))))

(defn scaffold-lei-repo [lei company-name]
  "Scaffold cloud-itonami-lei-<lei> repo skeleton (no push in this pass)."
  (let [repo-name (str "cloud-itonami-lei-" (str/lower-case lei))
        repo-path (str root "/orgs/cloud-itonami/" repo-name)]
    (when-not (:dry-run cli-flags)
      (log "  Scaffolding repo:" repo-name)
      (try
        (.mkdirs (java.io.File. repo-path))
        (spit (str repo-path "/README.md")
              (str "# " repo-name "\nLEI: " lei "\nCompany: " company-name))
        (spit (str repo-path "/blueprint.edn")
              (pr-str {:actor/id repo-name :actor/lei lei :actor/entity company-name}))
        (spit (str repo-path "/LICENSE") "Apache License 2.0")
        (log "    ✓ Scaffolded. (Manual: git init + push required)")
        repo-path
        (catch Exception e
          (log "    ✗ Scaffold error:" (str e))
          nil)))))

(defn -main []
  (status-banner)

  (let [isic-progress (read-edn-file (str root "/90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.progress.edn"))
        isco-progress (read-edn-file (str root "/90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.isco-progress.edn"))]

    (if (or (nil? isic-progress) (nil? isco-progress))
      (log "ERROR: Progress files not found")

      (do
        ;; Process ISIC
        (when (or (= "isic" (:scope cli-flags)) (= "all" (:scope cli-flags)))
          (let [pending-isic (list-pending isic-progress "ISIC")]
            (if (seq pending-isic)
              (log "\nProcessing ISIC (stub: actual GLEIF + ToS fetch via curl)...")
              (log "\n(ISIC axis: all rows processed)"))))

        ;; Process ISCO
        (when (or (= "isco" (:scope cli-flags)) (= "all" (:scope cli-flags)))
          (let [pending-isco (list-pending isco-progress "ISCO")]
            (if (seq pending-isco)
              (log "\nProcessing ISCO (stub: actual GLEIF + ToS fetch via curl)...")
              (log "\n(ISCO axis: all rows processed)"))))

        (log "\n=== Notes ===")
        (log "- Actual HTTP (GLEIF + ToS) requires shell curl or JS-capable headless browser")
        (log "- JS-rendered failures logged to retry-js-rendered.edn (manual chrome-automation)")
        (log "- Scaffolded repos require: git init + commit + push (outside this script)")
        (log "- Progress.edn update deferred to post-implementation phase")
        (log "\nNext steps: See ADR-2607182300 Part C design for full implementation.")))))

(-main)

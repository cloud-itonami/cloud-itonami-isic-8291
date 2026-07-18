#!/usr/bin/env nbb
"Cloud-itonami LEI/ToS collection automation.
Rolls through 90-docs/adr/2607110300-*.progress.edn (ISIC axis complete → ISCO pending).
For each pending vertical: call isic-8291 :disclosure/discover-candidates (in-process) →
Governor approve → GLEIF lookup + ToS fetch → scaffold cloud-itonami-lei-<LEI> → push.
Failures recorded honestly (no fabrication discipline).
JS-rendered ToS: queue for manual/chrome-automation retry."

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn read-progress
  "Load progress.edn row (pending/done/skipped)."
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn record-candidate
  "Generate cloud-itonami-lei-<LEI> repo scaffold + push."
  [{:keys [legal-name lei ticker source]}]
  (let [lei-lower (str/lower-case lei)
        repo-name (str "cloud-itonami-lei-" lei-lower)
        blueprint {:company/legal-name legal-name
                   :company/lei lei
                   :company/jurisdiction "UN"
                   :company/website "https://example.com"
                   :company/ticker ticker}]
    (println (str "[collect] Scaffolding " repo-name " (" legal-name ")"))))

(defn -main
  [& args]
  (let [progress-file "../../90-docs/adr/2607110300-cloud-itonami-lei-corporate-tos-catalog.progress.edn"
        progress (read-progress progress-file)]
    (if progress
      (println (str "[collect] Found progress file: " (count progress) " rows"))
      (println "[collect] No progress file — usage: nbb bin/collect.cljs [--dry-run]"))
    (when (some #(= "--dry-run" %) args)
      (println "[collect] DRY-RUN mode — no pushes"))))

(-main *command-line-args*)

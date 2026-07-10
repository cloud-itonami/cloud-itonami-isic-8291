(ns dossier.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the DisclosureGovernor's licensed-disclosure
  gate approved for the caller's contract tier (see `:disclosure/query`).
  This namespace only renders the approved columns, so a disclosure can
  never exceed the licensed tier — the Dun & Bradstreet/World-Check 'company
  profile' feature, with the tier-column policy fixed in code."
  (:require [clojure.string :as str]
            [dossier.store :as store]))

(defn render-profile
  "Render one company's profile over exactly `columns` (already governor-
  approved). `:officials`/`:relationships` are only ever rendered when the
  caller's tier included them."
  [db company-id columns]
  (let [c (store/company db company-id)
        cell (fn [col]
               (case col
                 :officials     (mapv :name (store/officials-of db company-id))
                 :relationships (mapv (juxt :from :to :kind) (store/relationships-of db company-id))
                 (get c col)))]
    (into {} (map (juxt identity cell)) columns)))

(defn relationship-graph-text
  "Plain-text relationship graph from a company/official/agency outward —
  the governed 'organization chart' view. Only ever called after the
  DisclosureGovernor has approved `:relationships` for the caller's tier."
  ([db root] (relationship-graph-text db root 0 #{}))
  ([db root depth seen]
   (if (contains? seen root)
     ""
     (let [name* (or (:legal-name (store/company db root))
                     (:name (store/official db root))
                     (:name (store/agency db root))
                     root)
           line (str (apply str (repeat depth "  ")) "└ " name*)
           edges (store/relationships-of db root)
           seen* (conj seen root)]
       (str/join "\n"
                 (cons line
                       (keep (fn [{:keys [from to kind]}]
                               (let [other (if (= root from) to from)]
                                 (when-not (contains? seen* other)
                                   (str (apply str (repeat (inc depth) "  "))
                                        "└ (" (name kind) ") "
                                        (relationship-graph-text db other (inc depth) seen*)))))
                             edges)))))))

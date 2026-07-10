(ns dossier.live-store
  "A `dossier.store/Store` decorator (ADR-2607110400 addendum 5) that layers
  ONE real live-data source — UK Companies House — on top of an underlying
  local store (`MemStore`/`DatomicStore`). Every protocol method not
  explicitly overridden below delegates straight through to `local` — this
  is a thin proxy, not a rewrite of the store contract.

  Precedence: local/seeded data wins when present (so demo fixtures and any
  operator-curated overrides still work exactly as before); a live
  Companies House lookup is consulted ONLY as a fallback when `local` has
  nothing for that id/name. This means `dossier.store/company`,
  `company-by-name` and `officials-of` behave identically to before for
  every id/name already in `local` — this decorator can only ADD coverage,
  never change an existing answer.

  `fetch-fn` is `nil`-safe throughout: pass `nil` (e.g. `COMPANIES_HOUSE_
  API_KEY` unset) and this behaves exactly like the undecorated `local`
  store — never throws, never silently invents a company. See
  `dossier.companies-house`'s docstring for the R0 scope this covers
  (company-by-name + officials-of a KNOWN company id; NOT a global
  official-by-name lookup — deferred, not silently missing)."
  (:require [dossier.store :as store]
            [dossier.companies-house :as ch]
            [clojure.string :as str]))

(defn- gbr-id? [id]
  (and (string? id) (str/starts-with? id "gbr-")))

(defn- company-number-of [gbr-id]
  (subs gbr-id 4))

(defrecord LiveGbrStore [local fetch-fn]
  store/Store
  (company [_ id]
    (or (store/company local id)
        (when (and fetch-fn (gbr-id? id))
          (ch/->company (ch/company-profile fetch-fn (company-number-of id))))))
  (company-by-name [_ name]
    (or (store/company-by-name local name)
        (when fetch-fn
          (ch/->company (ch/company-profile fetch-fn (:company_number (ch/find-company-by-name fetch-fn name)))))))
  (official [_ id] (store/official local id))
  (official-by-name [_ name] (store/official-by-name local name))
  (officials-of [_ org-id]
    (let [local-results (store/officials-of local org-id)]
      (if (seq local-results)
        local-results
        (if (and fetch-fn (gbr-id? org-id))
          (let [company-number (company-number-of org-id)]
            (->> (ch/company-officers fetch-fn company-number)
                 (mapv #(ch/->official org-id company-number %))
                 (sort-by :id)))
          local-results))))
  (agency [_ id] (store/agency local id))
  (relationships-of [_ id] (store/relationships-of local id))
  (contract [_ tenant] (store/contract local tenant))
  (ledger [_] (store/ledger local))
  (commit-record! [_ record] (store/commit-record! local record))
  (append-ledger! [_ fact] (store/append-ledger! local fact))
  (with-companies [_ cs] (store/with-companies local cs))
  (with-officials [_ os] (store/with-officials local os))
  (with-agencies [_ ags] (store/with-agencies local ags))
  (with-relationships [_ rs] (store/with-relationships local rs))
  (with-contracts [_ cts] (store/with-contracts local cts)))

(defn live-store
  "Wraps `local` (default: a fresh demo `store/seed-db`) with a live
  Companies House fallback. `fetch-fn` defaults to `dossier.companies-house/
  live-http-fn` built from `COMPANIES_HOUSE_API_KEY` when that env var is
  set, or `nil` (local-only, no live fallback at all) when it is not — an
  operator who hasn't configured a key gets EXACTLY the undecorated local
  store's behavior, never a crash."
  ([] (live-store (store/seed-db)))
  ([local]
   (let [key (ch/env-api-key)]
     (->LiveGbrStore local (when (ch/configured? key) #?(:clj (ch/live-http-fn key) :cljs nil)))))
  ([local fetch-fn] (->LiveGbrStore local fetch-fn)))

(ns dossier.live-store
  "A `dossier.store/Store` decorator (ADR-2607110400 addendum 5, GLEIF
  addendum pending) that layers real live-data sources — UK Companies
  House (`LiveGbrStore`) and GLEIF LEI (`LiveLeiStore`) — on top of an
  underlying local store (`MemStore`/`DatomicStore`). Every protocol
  method not explicitly overridden below delegates straight through to
  `local` — this is a thin proxy, not a rewrite of the store contract. The
  two decorators are independent record types and CHAIN (one's `local` can
  be the other) rather than merge into one type, so adding the GLEIF seam
  never touches `LiveGbrStore`'s own code or behavior.

  Precedence: local/seeded data wins when present (so demo fixtures and any
  operator-curated overrides still work exactly as before); a live lookup
  (GLEIF, then Companies House — see `live-store`'s docstring) is consulted
  ONLY as a fallback when `local` has nothing for that id/name. This means
  `dossier.store/company`, `company-by-name` and `officials-of` behave
  identically to before for every id/name already in `local` — this
  decorator can only ADD coverage, never change an existing answer.

  `fetch-fn` is `nil`-safe throughout: pass `nil` (e.g. `COMPANIES_HOUSE_
  API_KEY` unset) and this behaves exactly like the undecorated `local`
  store — never throws, never silently invents a company. See
  `dossier.companies-house`'s docstring for the CH R0 scope (company-by-
  name + officials-of a KNOWN company id; NOT a global official-by-name
  lookup — deferred, not silently missing) and `dossier.gleif`'s docstring
  for the GLEIF R0 scope (company + company-by-name only; GLEIF has no
  officer data at all, so `officials-of` is never GLEIF-sourced)."
  (:require [dossier.store :as store]
            [dossier.companies-house :as ch]
            [dossier.gleif :as gleif]
            [clojure.string :as str]))

(defn- gbr-id? [id]
  (and (string? id) (str/starts-with? id "gbr-")))

(defn- company-number-of [gbr-id]
  (subs gbr-id 4))

(defn- lei-id? [id]
  (and (string? id) (str/starts-with? id "lei-")))

(defn- lei-of [lei-id]
  (subs lei-id 4))

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

(defrecord LiveLeiStore [local fetch-fn]
  store/Store
  (company [_ id]
    (or (store/company local id)
        (when (and fetch-fn (lei-id? id))
          (gleif/->company (gleif/lei-record fetch-fn (lei-of id))))))
  (company-by-name [_ name]
    (or (store/company-by-name local name)
        (when fetch-fn
          (let [lei (get-in (gleif/find-lei-by-name fetch-fn name) [:attributes :lei])]
            (when lei (gleif/->company (gleif/lei-record fetch-fn lei)))))))
  (official [_ id] (store/official local id))
  (official-by-name [_ name] (store/official-by-name local name))
  ;; GLEIF LEI records carry no officer/director data at all -- this is a
  ;; pure passthrough, never a live lookup (unlike LiveGbrStore's
  ;; officials-of, which DOES have a real CH-backed fallback).
  (officials-of [_ org-id] (store/officials-of local org-id))
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

(defn lei-store
  "Wraps `local` with a live GLEIF LEI fallback ONLY — the GLEIF-only
  analog of `live-store`'s 2-arg CH-only arity, useful standalone or when
  composing manually. `fetch-fn` defaults to `dossier.gleif/live-http-fn`
  — GLEIF needs no key, so this is never nil in `:clj` unless a caller
  explicitly opts out by passing `nil`."
  ([] (lei-store (store/seed-db)))
  ([local] (->LiveLeiStore local #?(:clj (gleif/live-http-fn) :cljs nil)))
  ([local fetch-fn] (->LiveLeiStore local fetch-fn)))

(defn live-store
  "Wraps `local` (default: a fresh demo `store/seed-db`) with this actor's
  live-data fallbacks, chained local-first: an id/name absent from `local`
  falls through to GLEIF LEI, then (if still absent) to Companies House —
  arbitrary order between the two live sources, but local ALWAYS wins over
  either (see ns docstring). `company`/`company-by-name` benefit from
  both; `officials-of` only ever gets a live answer from Companies House
  (GLEIF has no officer data — see `LiveLeiStore`'s docstring).

    - 0-arg / 1-arg: convenience constructors that chain BOTH live sources,
      resolving each fetch-fn from its own default: `dossier.companies-
      house/live-http-fn` gated on `COMPANIES_HOUSE_API_KEY` (nil, i.e. no
      CH fallback, when that env var is unset) and `dossier.gleif/live-
      http-fn` (needs no key, so always live in `:clj` unless a caller
      opts out via the 3-arg arity below).
    - `[local ch-fetch-fn]`: UNCHANGED since ADR addendum 5 — Companies-
      House-only, no GLEIF layer at all. This is the exact 2-arg shape
      `test/dossier/live_store_test.clj` exercises with a canned CH-shaped
      fake and it must keep behaving identically; use `lei-store` for the
      GLEIF-only equivalent, or the 3-arg arity below for both.
    - `[local ch-fetch-fn lei-fetch-fn]`: explicit control over both
      sources at once (either may be `nil` to disable that source)."
  ([] (live-store (store/seed-db)))
  ([local]
   (live-store local
               (let [key (ch/env-api-key)]
                 (when (ch/configured? key) #?(:clj (ch/live-http-fn key) :cljs nil)))
               #?(:clj (gleif/live-http-fn) :cljs nil)))
  ([local ch-fetch-fn] (->LiveGbrStore local ch-fetch-fn))
  ([local ch-fetch-fn lei-fetch-fn]
   (->LiveGbrStore (->LiveLeiStore local lei-fetch-fn) ch-fetch-fn)))

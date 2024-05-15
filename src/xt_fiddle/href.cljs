(ns xt-fiddle.href
  (:require [re-frame.core :as rf]))

(rf/reg-cofx ::get
  (fn [cofx _]
    (assoc cofx :href js/window.location.href)))

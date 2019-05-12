(ns openmind.events
	(:require
	 [re-frame.core :as re-frame]
	 [openmind.db :as db]
	 ))

(re-frame/reg-event-db
 ::initialize-db
 (fn [_ _]
	 db/default-db))

#_(re-frame/reg-event-db
 )

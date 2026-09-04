// ems_frontend/src/store/store.js
import { configureStore, combineReducers } from '@reduxjs/toolkit'
import { persistStore, persistReducer, createTransform } from 'redux-persist'
import storage from 'redux-persist/lib/storage'
import authReducer, { loginSuccess, logout } from './slices/authSlice'
import userReducer from './slices/userSlice'
import certificationReducer from './slices/certificationSlice'
import examReducer from './slices/examSlice'
import paymentReducer from './slices/paymentSlice'
import certificateReducer from './slices/certificateSlice'
import proctoringReducer from './slices/proctoringSlice'
import reportReducer from './slices/reportSlice'
import adminReducer from './slices/adminSlice'

const appReducer = combineReducers({
  auth: authReducer,
  user: userReducer,
  certification: certificationReducer,
  exam: examReducer,
  payment: paymentReducer,
  certificate: certificateReducer,
  proctoring: proctoringReducer,
  report: reportReducer,
  admin: adminReducer
})

/**
 * Wipes every slice back to its initial state when a session begins or ends.
 *
 * Only `auth` and `user` are persisted, but `user.profile` (name, email, photo
 * shown in the header and sidebar) outlives a logout because `logout` clears
 * `auth` alone. The next person to sign in then sees the previous account's
 * identity — and stale admin tables, exam progress, etc. from the other slices —
 * until each screen happens to refetch. Resetting on the way in *and* out is the
 * single place that covers all of it: `loginSuccess` self-heals a client that
 * already has stale data on disk, `logout` stops it being written in the first
 * place. Passing `undefined` makes combineReducers hand each slice its own
 * initialState; the triggering action still runs (so `loginSuccess` repopulates
 * `auth`), and redux-persist flushes the cleared shape to storage.
 */
const rootReducer = (state, action) => {
  if (action.type === loginSuccess.type || action.type === logout.type) {
    return appReducer(undefined, action)
  }
  return appReducer(state, action)
}

/**
 * Strips transient request-lifecycle flags before they reach localStorage.
 *
 * `isLoading` / `error` / `success` are true only for the lifetime of an
 * in-flight request. If the request never settles — e.g. the network drops
 * before it errors — and the persisted slice still has `isLoading: true` on
 * disk, a page refresh rehydrates that stale `true` with no request left to
 * ever flip it back. A submit button bound to it (see LoginPage) then stays
 * disabled/spinning forever, until the user manually clears storage.
 *
 * `inbound` runs on the way INTO storage: dropping the keys here means they
 * are simply absent from what's written. On rehydration redux-persist merges
 * the persisted object over the slice's initialState (autoMergeLevel1), so a
 * missing key leaves the freshly-booted `false` / `null` untouched instead of
 * being overwritten by a stale value.
 */
const stripTransientRequestState = createTransform(
  (inboundState) => {
    const { isLoading, error, success, ...persisted } = inboundState || {}
    return persisted
  },
  (outboundState) => outboundState,
  { whitelist: ['auth', 'user'] }
)

const persistConfig = {
  key: 'root',
  storage,
  whitelist: ['auth', 'user'],
  transforms: [stripTransientRequestState]
}

const persistedReducer = persistReducer(persistConfig, rootReducer)

const store = configureStore({
  reducer: persistedReducer,
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware({
      serializableCheck: {
        ignoredActions: ['persist/PERSIST']
      }
    })
})

export const persistor = persistStore(store)
export default store

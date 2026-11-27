// SPDX-License-Identifier: MIT
package lenovotouch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Binder client for {@code com.google.android.things.sparrow.oem/.SparrowService}
 * (exported OEM service on Lenovo Smart Clock Gen 1).
 *
 * <p>Uses the exact AIDL wire protocol verified from {@code com.google.android.things.sparrow.oem}:
 * <ul>
 *   <li>Bind intent: {@code com.google.sparrow.START_SPARROW_SERVICE} (package {@code com.google.android.things.sparrow.oem})</li>
 *   <li>{@code com.google.sparrow.ICoreServicesInterface} transaction 12: {@code registerTapEventListener(ITapEventListener)}</li>
 *   <li>{@code com.google.sparrow.ITapEventListener} transaction 1: {@code onTapEvent(int event)} (0 = SINGLE_TAP, 1 = DOUBLE_TAP)</li>
 * </ul>
 */
public final class SparrowTapClient {
    public static final String SPARROW_PACKAGE = "com.google.android.things.sparrow.oem";
    public static final String SPARROW_ACTION = "com.google.sparrow.START_SPARROW_SERVICE";
    private static final String CORE_DESCRIPTOR = "com.google.sparrow.ICoreServicesInterface";
    private static final String TAP_LISTENER_DESCRIPTOR = "com.google.sparrow.ITapEventListener";
    private static final int TRANSACTION_REGISTER_TAP_LISTENER = 12;
    private static final int TRANSACTION_ON_TAP_EVENT = 1;

    public interface Callback {
        void onConnected();
        void onDisconnected(String reason);
        void onTap(int rawEvent, long elapsedRealtimeMs);
    }

    private final Context appContext;
    private final ScheduledExecutorService scheduler;
    private final Callback callback;

    private volatile boolean started;
    private volatile boolean bound;
    private volatile IBinder remoteBinder;
    private ScheduledFuture<?> reconnectFuture;
    private int reconnectAttempt;

    private final TapListenerBinder tapListener = new TapListenerBinder();

    private final IBinder.DeathRecipient deathRecipient = this::onBinderDied;

    private void onBinderDied() {
        remoteBinder = null;
        if (started) {
            callback.onDisconnected("SparrowService died; reconnecting");
            scheduleReconnect();
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!started) {
                safeUnbind();
                return;
            }
            remoteBinder = service;
            reconnectAttempt = 0;
            try {
                service.linkToDeath(deathRecipient, 0);
            } catch (RemoteException ignored) {
                // Will fail registration below and schedule reconnect.
            }
            try {
                registerTapListener(service);
                callback.onConnected();
            } catch (Throwable t) {
                remoteBinder = null;
                callback.onDisconnected("Failed to register tap listener: " + t.getMessage());
                scheduleReconnect();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remoteBinder = null;
            if (started) {
                callback.onDisconnected("SparrowService disconnected; reconnecting");
                scheduleReconnect();
            }
        }
    };

    public SparrowTapClient(Context context, ScheduledExecutorService scheduler, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.scheduler = scheduler;
        this.callback = callback;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        reconnectAttempt = 0;
        bindNow();
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
        IBinder remote = remoteBinder;
        remoteBinder = null;
        if (remote != null) {
            try {
                remote.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
                // Already dead or not linked.
            }
        }
        safeUnbind();
    }

    public boolean isConnected() {
        IBinder r = remoteBinder;
        return started && r != null && r.isBinderAlive();
    }

    private synchronized void bindNow() {
        if (!started) return;
        safeUnbind();
        Intent intent = new Intent(SPARROW_ACTION);
        intent.setPackage(SPARROW_PACKAGE);
        try {
            boolean ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            bound = ok;
            if (!ok) {
                callback.onDisconnected("SparrowService not found on this device");
                scheduleReconnect();
            }
        } catch (SecurityException e) {
            bound = false;
            callback.onDisconnected("Permission denied binding SparrowService: " + e.getMessage());
        } catch (Throwable t) {
            bound = false;
            callback.onDisconnected("Error binding SparrowService: " + t.getMessage());
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (!started) return;
        if (reconnectFuture != null && !reconnectFuture.isDone()) return;
        long delaySec = Math.min(30L, 1L << Math.min(4, reconnectAttempt++));
        reconnectFuture = scheduler.schedule(this::bindNow, delaySec, TimeUnit.SECONDS);
    }

    private synchronized void safeUnbind() {
        if (!bound) return;
        bound = false;
        try {
            appContext.unbindService(connection);
        } catch (Throwable ignored) {
            // Service may already be unbound.
        }
    }

    private void registerTapListener(IBinder service) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CORE_DESCRIPTOR);
            data.writeStrongBinder(tapListener);
            boolean ok = service.transact(TRANSACTION_REGISTER_TAP_LISTENER, data, reply, 0);
            if (!ok) {
                throw new RemoteException("SparrowService rejected registerTapEventListener");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private final class TapListenerBinder extends Binder {
        TapListenerBinder() {
            attachInterface(null, TAP_LISTENER_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(TAP_LISTENER_DESCRIPTOR);
                return true;
            }
            if (code == TRANSACTION_ON_TAP_EVENT) {
                data.enforceInterface(TAP_LISTENER_DESCRIPTOR);
                int event = data.readInt();
                reply.writeNoException();
                if (started) {
                    callback.onTap(event, SystemClock.elapsedRealtime());
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}

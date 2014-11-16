package com.serenegiant.service;
/*
 * UVCCamera
 * library and sample to access to UVC web camera on non-rooted Android device
 * 
 * Copyright (c) 2014 saki t_saki@serenegiant.com
 * 
 * File name: UVCService.java
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * 
 * All files in the folder are under this Apache License, Version 2.0.
 * Files in the jni/libjpeg, jni/libusb and jin/libuvc folder may have a different license, see the respective files.
*/

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;

import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

public class UVCService extends Service {
	private static final boolean DEBUG = true;
	private static final String TAG = "UVCService";

	@SuppressWarnings("unused")
	private Handler mServiceHandler;
	private USBMonitor mUSBMonitor;

	public UVCService() {
		if (DEBUG) Log.d(TAG, "Constructor:");
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (DEBUG) Log.d(TAG, "onCreate:");
		mServiceHandler = new Handler();
		mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
		mUSBMonitor.register();
	}

	@Override
	public void onDestroy() {
		if (DEBUG) Log.d(TAG, "onDestroy:");
		checkReleaseService();
		mUSBMonitor.unregister();
		mUSBMonitor = null;
		mServiceHandler = null;
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		if (DEBUG) Log.d(TAG, "onBind:" + intent);
		if (IUVCService.class.getName().equals(intent.getAction())) {
			Log.i(TAG, "return mBasicBinder");
			return mBasicBinder;
		}
		if (IUVCSlaveService.class.getName().equals(intent.getAction())) {
			Log.i(TAG, "return mSlaveBinder");
			return mSlaveBinder;
		}
		return null;
	}

	@Override
	public void onRebind(Intent intent) {
		if (DEBUG) Log.d(TAG, "onRebind:" + intent);
	}

	@Override
	public boolean onUnbind(Intent intent) {
		if (DEBUG) Log.d(TAG, "onUnbind:" + intent);
		checkReleaseService();
		return true;
	}

//********************************************************************************
	private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
		@Override
		public void onAttach(UsbDevice device) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onAttach:");
		}

		@Override
		public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, boolean createNew) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onConnect:");

			final int key = device.hashCode(); 
			CameraServer service;
			synchronized (sServiceSync) {
				service = sCameraServers.get(key);
				if (service == null) {
					service = CameraServer.createServer(UVCService.this, ctrlBlock, device.getVendorId(), device.getProductId());
					sCameraServers.append(key, service);
				} else {
					Log.w(TAG, "service already exist before connection");
				}
				sServiceSync.notifyAll();
			}
		}

		@Override
		public void onDisconnect(UsbDevice device, UsbControlBlock ctrlBlock) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onDisconnect:");
			final int key = device.hashCode();		
			synchronized (sServiceSync) {
				final CameraServer service = sCameraServers.get(key);
				if (service != null)
					service.release();
				sCameraServers.remove(key);
				sServiceSync.notifyAll();
			}
		}

		@Override
		public void onDettach(UsbDevice device) {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onDettach:");
		}

		@Override
		public void onCancel() {
			if (DEBUG) Log.d(TAG, "OnDeviceConnectListener#onCancel:");
			synchronized (sServiceSync) {
				sServiceSync.notifyAll();
			}
		}
	};

//********************************************************************************
	private static final Object sServiceSync = new Object();
	private static final SparseArray<CameraServer> sCameraServers = new SparseArray<CameraServer>();

	/**
	 * get CameraService that has specific ID<br>
	 * if zero is provided as ID, just return top of CameraServer instance(non-blocking method) if exists or null.<br>
	 * if non-zero ID is provided, return specific CameraService if exist. block if not exists.<br>
	 * return null if not exist matched specific ID<br>
	 * @param serviceId
	 * @return
	 */
	private static CameraServer getCameraServer(int serviceId) {
		synchronized (sServiceSync) {
			CameraServer server = null;
			if ((serviceId == 0) && (sCameraServers.size() > 0)) {
				server = sCameraServers.valueAt(0);
			} else {
				server = sCameraServers.get(serviceId);
				if (server == null)
					try {
						Log.i(TAG, "waitting for service is ready");
						sServiceSync.wait();
					} catch (InterruptedException e) {
					}
					server = sCameraServers.get(serviceId);
			}
			return server; 
		}
	}

	private static void checkReleaseService() {
		CameraServer server = null;
		synchronized (sServiceSync) {
			final int n = sCameraServers.size();
			if (DEBUG) Log.d(TAG, "checkReleaseService:number of service=" + n);
			for (int i = 0; i < n; i++) {
				server = sCameraServers.valueAt(i);
				if (server != null && !server.isConnected()) {
					sCameraServers.removeAt(i);
					server.release();
				}
			}
		}
	}

//********************************************************************************
	private final IUVCService.Stub mBasicBinder = new IUVCService.Stub() {
		private IUVCServiceCallback mCallback;

		@Override
		public int select(UsbDevice device, IUVCServiceCallback callback) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#select:device=" + device);
			mCallback = callback;
			final int serviceId = device.hashCode();
			CameraServer server = null;
			synchronized (sServiceSync) {
				server = sCameraServers.get(serviceId);
				if (server == null) {
					Log.i(TAG, "request permission");
					mUSBMonitor.requestPermission(device);
					Log.i(TAG, "wait for getting permission");
					try {
						sServiceSync.wait();
					} catch (Exception e) {
						Log.e(TAG, "connect:", e);
					}
					Log.i(TAG, "check service again");
					server = sCameraServers.get(serviceId);
					if (server == null) {
						throw new RuntimeException("failed to open USB device(has no permission)");
					}
				}
			}
			if (server != null) {
				Log.i(TAG, "success to get service:serviceId=" + serviceId);
				server.registerCallback(callback);
			}
			return serviceId;
		}

		@Override
		public void release(int serviceId) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#release:");
			synchronized (sServiceSync) {
				final CameraServer server = sCameraServers.get(serviceId);
				if (server != null) {
					if (server.unregisterCallback(mCallback)) {
						if (!server.isConnected()) {
							sCameraServers.remove(serviceId);
							if (server != null) {
								server.release();
							}
						}
					}
				}
			}
			mCallback = null;
		}

		@Override
		public boolean isSelected(int serviceId) throws RemoteException {
			return getCameraServer(serviceId) != null;
		}

		@Override
		public void releaseAll() throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#releaseAll:");
			CameraServer server;
			synchronized (sServiceSync) {
				final int n = sCameraServers.size();
				for (int i = 0; i < n; i++) {
					server = sCameraServers.valueAt(i);
					sCameraServers.removeAt(i);
					if (server != null) {
						server.release();
					}
				}
			}
		}

		@Override
		public void connect(int serviceId) throws RemoteException {

			if (DEBUG) Log.d(TAG, "mBasicBinder#connect:");
			final CameraServer server = getCameraServer(serviceId);
			if (server == null) {
				throw new IllegalArgumentException("invalid serviceId");
			}
			server.connect();
		}

		@Override
		public void disconnect(int serviceId) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#disconnect:");
			final CameraServer server = getCameraServer(serviceId);
			if (server == null) {
				throw new IllegalArgumentException("invalid serviceId");
			}
			server.disconnect();
		}

		@Override
		public boolean isConnected(int serviceId) throws RemoteException {
			final CameraServer server = getCameraServer(serviceId);
			return (server != null) && server.isConnected();
		}

		@Override
		public void addSurface(int serviceId, int id_surface, Surface surface, boolean isRecordable) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#addSurface:id=" + id_surface + ",surface=" + surface);
			final CameraServer server = getCameraServer(serviceId);
			if (server != null)
				server.addSurface(id_surface, surface, isRecordable, null);
		}

		@Override
		public void removeSurface(int serviceId, int id_surface) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mBasicBinder#removeSurface:id=" + id_surface);
			final CameraServer server = getCameraServer(serviceId);
			if (server != null)
				server.removeSurface(id_surface);
		}

		@Override
		public boolean isRecording(int serviceId) throws RemoteException {
			final CameraServer server = getCameraServer(serviceId);
			return server != null && server.isRecording();
		}

		@Override
		public void startRecording(int serviceId) throws RemoteException {
			final CameraServer server = getCameraServer(serviceId);
			if ((server != null) && !server.isRecording()) {
				server.startRecording();
			}
		}

		@Override
		public void stopRecording(int serviceId) throws RemoteException {
			final CameraServer server = getCameraServer(serviceId);
			if ((server != null) && server.isRecording()) {
				server.stopRecording();
			}
		}

    };

//********************************************************************************
	private final IUVCSlaveService.Stub mSlaveBinder = new IUVCSlaveService.Stub() {
		@Override
		public boolean isSelected(int serviceID) throws RemoteException {
			return getCameraServer(serviceID) != null;
		}

		@Override
		public boolean isConnected(int serviceID) throws RemoteException {
			final CameraServer server = getCameraServer(serviceID);
			return server != null ? server.isConnected() : false;
		}

		@Override
		public void addSurface(int serviceID, int id_surface, Surface surface, boolean isRecordable, IUVCServiceOnFrameAvailable callback) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mSlaveBinder#addSurface:id=" + id_surface + ",surface=" + surface);
			final CameraServer server = getCameraServer(serviceID);
			if (server != null) {
				server.addSurface(id_surface, surface, isRecordable, callback);
			} else {
				Log.e(TAG, "failed to get CameraServer:serviceID=" + serviceID);
			}
		}

		@Override
		public void removeSurface(int serviceID, int id_surface) throws RemoteException {
			if (DEBUG) Log.d(TAG, "mSlaveBinder#removeSurface:id=" + id_surface);
			final CameraServer server = getCameraServer(serviceID);
			if (server != null) {
				server.removeSurface(id_surface);
			} else {
				Log.e(TAG, "failed to get CameraServer:serviceID=" + serviceID);
			}
		}
	};

}
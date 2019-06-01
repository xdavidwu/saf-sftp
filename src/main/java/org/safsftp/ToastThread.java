package org.safsftp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.Toast;

public class ToastThread extends Thread {
	public Handler handler;
	private Context context;

	public ToastThread(Context context){
		this.context=context;
	}

	public void run() {
		Looper.prepare();
		handler=new Handler() {
			public void handleMessage(Message msg) {
				Toast.makeText(context,(String)msg.obj,Toast.LENGTH_SHORT).show();
			}
		};
		Looper.loop();
	}
}


package org.safsftp;

import android.os.AsyncTask;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.util.Log;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.SFTPv3FileHandle;

import org.safsftp.ToastThread;

public class ReadTask extends AsyncTask<Void,Void,Void> {
	private Connection connection;
	private SFTPv3Client sftp;
	private SFTPv3FileHandle file;
	private ToastThread lthread;
	private ParcelFileDescriptor fd;

	public ReadTask(String host,String port,String username,String passwd,
			String filename,ParcelFileDescriptor fd,ToastThread lthread) {
		this.fd=fd;
		try {
			connection=new Connection(host,Integer.parseInt(port));
			connection.connect(null,10000,10000);
			if(!connection.authenticateWithPassword(username,passwd)){
				Message msg=lthread.handler.obtainMessage();
				msg.obj="SFTP auth failed.";
				lthread.handler.sendMessage(msg);
			}
			sftp=new SFTPv3Client(connection);
			sftp.setCharset(null);
			Message msg=lthread.handler.obtainMessage();
			msg.obj="SFTP connect succeed.";
			lthread.handler.sendMessage(msg);
			file=sftp.openFileRO(filename);
		}
		catch(Exception e){
			Message msg=lthread.handler.obtainMessage();
			msg.obj=e.toString();
			lthread.handler.sendMessage(msg);
			sftp.close();
			connection.close();
		}
	}

	@Override
	public Void doInBackground(Void... args) {
		AutoCloseOutputStream acos=new AutoCloseOutputStream(fd);
		int size,offset=0;
		byte[] buf=new byte[32768];
		try{
			while((size=sftp.read(file,offset,buf,0,32768))>0) {
				acos.write(buf,0,size);
				offset+=size;
			}
			sftp.closeFile(file);
			sftp.close();
			connection.close();
			acos.close();
		}
		catch(Exception e){
			Log.e("SFTP","read file "+e.toString());
			sftp.close();
			connection.close();
		}
		return null;
	}
}

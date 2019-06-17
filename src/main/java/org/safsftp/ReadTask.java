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
	private SFTPv3Client sftp;
	private SFTPv3FileHandle file;
	private ParcelFileDescriptor fd;

	public ReadTask(SFTPv3Client sftp,SFTPv3FileHandle file,ParcelFileDescriptor fd) {
		this.fd=fd;
		this.sftp=sftp;
		this.file=file;
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
			acos.close();
		}
		catch(Exception e){
			Log.e("SFTP","read file "+e.toString());
		}
		try{
			sftp.closeFile(file);
		}
		catch(Exception e){
			Log.e("SFTP","close file "+e.toString());
		}
		return null;
	}
}

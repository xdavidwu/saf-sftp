package org.safsftp;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.util.Log;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.SFTPv3DirectoryEntry;
import com.trilead.ssh2.SFTPv3FileAttributes;

import java.io.IOException;
import java.util.Vector;

import org.safsftp.ToastThread;
import org.safsftp.ReadTask;

public class SFTPDocumentsProvider extends DocumentsProvider {

	private ToastThread lthread;
	
	private static final String[] DEFAULT_ROOT_PROJECTION=new String[]{
		Root.COLUMN_ROOT_ID,
		Root.COLUMN_FLAGS,
		Root.COLUMN_ICON,
		Root.COLUMN_TITLE,
		Root.COLUMN_DOCUMENT_ID,
	};

	private static final String[] DEFAULT_DOC_PROJECTION=new String[]{
		Document.COLUMN_DOCUMENT_ID,
		Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE,
		Document.COLUMN_LAST_MODIFIED,
		Document.COLUMN_SIZE
	};

	private static String getMime(String filename) {
		int idx=filename.lastIndexOf(".");
		if(idx>0){
			String mime=MimeTypeMap.getSingleton()
				.getMimeTypeFromExtension(filename
				.substring(idx+1).toLowerCase());
			if(mime!=null)return mime;
		}
		return "application/octet-stream";
	}

	@Override
	public boolean onCreate() {
		lthread=new ToastThread(getContext());
		lthread.start();
		return true;
	}

	public ParcelFileDescriptor openDocument(String documentId,
			String mode,CancellationSignal cancellationSignal) {
		if (!"r".equals(mode)) {
			throw new UnsupportedOperationException("Mode "+mode+" is not supported yet.");
		}
		SharedPreferences settings=PreferenceManager
			.getDefaultSharedPreferences(getContext());
		String host=documentId.substring(0,documentId.indexOf(":"));
		String tmp=documentId.substring(0,documentId.indexOf("/"));
		String port=tmp.substring(tmp.indexOf(":")+1);
		String filename=documentId.substring(documentId.indexOf("/")+1);
		Log.e("SFTP","od "+documentId+" on "+host+":"+port);
		ParcelFileDescriptor[] fd;
		try {
			fd=ParcelFileDescriptor.createReliablePipe();
		}
		catch(IOException e) {
			return null;
		}
		new ReadTask(host,port,settings.getString("username",""),
				settings.getString("passwd",""),
				filename,fd[1],lthread).execute();
		Log.e("SFTP","od "+documentId+" on "+host+":"+port+" return");
		return fd[0];
	}

	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection,String sortOrder) {
		MatrixCursor result=new MatrixCursor(projection!=null?projection:DEFAULT_DOC_PROJECTION);
		SharedPreferences settings=PreferenceManager
			.getDefaultSharedPreferences(getContext());
		Connection connection=null;
		SFTPv3Client sftp=null;
		String host=parentDocumentId.substring(0,parentDocumentId.indexOf(":"));
		String tmp=parentDocumentId.substring(0,parentDocumentId.indexOf("/"));
		String port=tmp.substring(tmp.indexOf(":")+1);
		Log.e("SFTP","qcf "+parentDocumentId+" on "+host+":"+port);
		try {
			connection=new Connection(host,Integer.parseInt(port));
			connection.connect(null,10000,10000);
			if(!connection.authenticateWithPassword(settings.getString("username",""),
				settings.getString("passwd",""))){
				Message msg=lthread.handler.obtainMessage();
				msg.obj="SFTP auth failed.";
				lthread.handler.sendMessage(msg);
			}
			sftp=new SFTPv3Client(connection);
			sftp.setCharset(null);
			Message msg=lthread.handler.obtainMessage();
			msg.obj="SFTP connect succeed.";
			lthread.handler.sendMessage(msg);
		}
		catch(Exception e){
			Message msg=lthread.handler.obtainMessage();
			msg.obj=e.toString();
			lthread.handler.sendMessage(msg);
			sftp.close();
			connection.close();
			return result;
		}
		String filename=parentDocumentId.substring(parentDocumentId.indexOf("/")+1);
		try{
			Vector<SFTPv3DirectoryEntry> res=sftp.ls(filename);
			for(SFTPv3DirectoryEntry entry : res){
				Log.e("SFTP","qcf "+parentDocumentId+" "+entry.filename+" "+entry.attributes.size+" "+entry.attributes.mtime);
				if(entry.filename.equals(".")||entry.filename.equals(".."))continue;
				MatrixCursor.RowBuilder row=result.newRow();
				row.add(Document.COLUMN_DOCUMENT_ID,parentDocumentId+'/'+entry.filename);
				row.add(Document.COLUMN_DISPLAY_NAME,entry.filename);
				if(entry.attributes.isDirectory()){
					row.add(Document.COLUMN_MIME_TYPE,Document.MIME_TYPE_DIR);
				}
				else if(entry.attributes.isRegularFile()){
					row.add(Document.COLUMN_MIME_TYPE,getMime(entry.filename));
				}
				row.add(Document.COLUMN_SIZE,entry.attributes.size);
				row.add(Document.COLUMN_LAST_MODIFIED,entry.attributes.mtime*1000);
			}
		}
		catch(Exception e){
		Log.e("SFTP","qcf "+parentDocumentId+" "+e.toString());
			Message msg=lthread.handler.obtainMessage();
			msg.obj=e.toString();
			lthread.handler.sendMessage(msg);
		}
		sftp.close();
		connection.close();
		return result;
	}

	public Cursor queryDocument(String documentId, String[] projection) {
		MatrixCursor result=new MatrixCursor(projection!=null?projection:DEFAULT_DOC_PROJECTION);
		SharedPreferences settings=PreferenceManager
			.getDefaultSharedPreferences(getContext());
		Connection connection=null;
		SFTPv3Client sftp=null;
		String host=documentId.substring(0,documentId.indexOf(":"));
		String tmp=documentId.substring(0,documentId.indexOf("/"));
		String port=tmp.substring(tmp.indexOf(":")+1);
		Log.e("SFTP","qf "+documentId+" on "+host+":"+port);
		try {
			connection=new Connection(host,Integer.parseInt(port));
			connection.connect(null,10000,10000);
			if(!connection.authenticateWithPassword(settings.getString("username",""),
				settings.getString("passwd",""))){
				Message msg=lthread.handler.obtainMessage();
				msg.obj="SFTP auth failed.";
				lthread.handler.sendMessage(msg);
			}
			sftp=new SFTPv3Client(connection);
			sftp.setCharset(null);
			Message msg=lthread.handler.obtainMessage();
			msg.obj="SFTP connect succeed.";
			lthread.handler.sendMessage(msg);
		}
		catch(Exception e){
			Message msg=lthread.handler.obtainMessage();
			msg.obj=e.toString();
			lthread.handler.sendMessage(msg);
			sftp.close();
			connection.close();
			return result;
		}
		String filename=documentId.substring(documentId.indexOf("/")+1);
		try{
			SFTPv3FileAttributes res=sftp.stat(filename);
			MatrixCursor.RowBuilder row=result.newRow();
			row.add(Document.COLUMN_DOCUMENT_ID,documentId);
			row.add(Document.COLUMN_DISPLAY_NAME,filename.substring(filename.lastIndexOf("/")+1));
			row.add(Document.COLUMN_MIME_TYPE,res.isDirectory()?Document.MIME_TYPE_DIR:getMime(filename));
			row.add(Document.COLUMN_SIZE,res.size);
			row.add(Document.COLUMN_LAST_MODIFIED,res.mtime*1000);
		}
		catch(Exception e){
		Log.e("SFTP","qf "+documentId+" "+e.toString());
			Message msg=lthread.handler.obtainMessage();
			msg.obj=e.toString();
			lthread.handler.sendMessage(msg);
		}
		sftp.close();
		connection.close();
		return result;
	}

	public Cursor queryRoots(String[] projection) {
		MatrixCursor result=new MatrixCursor(projection!=null?projection:DEFAULT_ROOT_PROJECTION);
		SharedPreferences settings=PreferenceManager
			.getDefaultSharedPreferences(getContext());
		String host=settings.getString("host",""),port=settings.getString("port","22");
		MatrixCursor.RowBuilder row=result.newRow();
		row.add(Root.COLUMN_ROOT_ID,host+":"+port);
		row.add(Root.COLUMN_DOCUMENT_ID,host+":"+port+"/.");
		row.add(Root.COLUMN_FLAGS,0);
		row.add(Root.COLUMN_TITLE,"SFTP "+host+":"+port);
		row.add(Root.COLUMN_ICON,R.mipmap.sym_def_app_icon);
		return result;
	}
}

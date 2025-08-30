package link.xdavidwu.saf.sftp;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
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
import com.trilead.ssh2.SFTPv3FileHandle;

import java.io.IOException;
import java.util.Locale;
import java.util.Vector;

public class SFTPDocumentsProvider extends DocumentsProvider {
	private Connection connection;
	private String host = null, port = null; // TODO: multiple server support
	private StorageManager sm;
	private Handler ioHandler;
	private ToastThread lthread;

	private static final String TOAST_PREFIX = "SAF-SFTP: ";

	private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
		Root.COLUMN_ROOT_ID,
		Root.COLUMN_FLAGS,
		Root.COLUMN_ICON,
		Root.COLUMN_TITLE,
		Root.COLUMN_DOCUMENT_ID,
		Root.COLUMN_SUMMARY,
	};

	private static final String[] DEFAULT_DOC_PROJECTION = new String[] {
		Document.COLUMN_DOCUMENT_ID,
		Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE,
		Document.COLUMN_LAST_MODIFIED,
		Document.COLUMN_SIZE,
		Document.COLUMN_FLAGS,
	};

	private static String getMime(String filename) {
		int idx = filename.lastIndexOf(".");
		if (idx > 0) {
			String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
				filename.substring(idx + 1).toLowerCase(Locale.ROOT));
			if (mime != null) {
				return mime;
			}
		}
		return "application/octet-stream";
	}

	private void toast(String msg) {
		Message m = lthread.handler.obtainMessage();
		m.obj = TOAST_PREFIX + msg;
		lthread.handler.sendMessage(m);
	}

	private void throwOrAddErrorExtra(String msg, Cursor cursor) {
		if (cursor != null) {
			Bundle extra = new Bundle();
			extra.putString(DocumentsContract.EXTRA_ERROR, msg);
			cursor.setExtras(extra);
		} else {
			toast(msg);
			throw new IllegalStateException(msg);
		}
	}

	private SFTPv3Client retriveConnection(Cursor cursor) {
		if (connection != null) {
			try {
				connection.ping();
				return new SFTPv3Client(connection);
			} catch (IOException e) {
				// continue with new connection attempt
			}
			connection.close();
		}
		SharedPreferences settings =
			PreferenceManager.getDefaultSharedPreferences(getContext());
		if (host == null || port == null) {
			host = settings.getString("host", "");
			port = settings.getString("port", "22");
		}
		Log.v("SFTP", "connect " + host + ":" + port);
		connection = new Connection(host, Integer.parseInt(port));
		try {
			connection.connect(null, 10000, 10000);
			if (!connection.authenticateWithPassword(settings.getString("username", ""),
				    settings.getString("passwd", ""))) {
				throwOrAddErrorExtra("Authentication failed.", cursor);
				connection.close();
				connection = null;
				return null;
			}
			return new SFTPv3Client(connection);
		} catch (IOException e) {
			throwOrAddErrorExtra("connect: " + e.toString(), cursor);
			connection.close();
			connection = null;
		}
		return null;
	}

	private void editPolicyIfMainThread() {
		// FIXME
		// if(Looper.getMainLooper()==Looper.myLooper()){
		//	Log.w("SFTP","We're on main thread.");
		StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);
		//}
	}

	@Override
	public boolean onCreate() {
		sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
		lthread = new ToastThread(getContext());
		lthread.start();
		HandlerThread ioThread = new HandlerThread("IO thread");
		ioThread.start();
		ioHandler = new Handler(ioThread.getLooper());
		return true;
	}

	public ParcelFileDescriptor openDocument(
		String documentId, String mode, CancellationSignal cancellationSignal) {
		if (!"r".equals(mode)) {
			throw new UnsupportedOperationException(
				"Mode " + mode + " is not supported yet.");
		}
		editPolicyIfMainThread();
		SFTPv3Client sftp = retriveConnection(null);
		String filename = documentId.substring(documentId.indexOf("/") + 1);
		Log.v("SFTP", "od " + documentId + " on " + host + ":" + port);
		try {
			SFTPv3FileHandle file = sftp.openFileRO(filename);
			return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
				new SFTPProxyFileDescriptorCallback(sftp, file), ioHandler);
		} catch (IOException e) {
			// throws
			throwOrAddErrorExtra("open (ro): " + e.toString(), null);
			return null;
		}
	}

	private void fillStatRow(MatrixCursor.RowBuilder row, String name,
			SFTPv3FileAttributes stat) {
		row.add(Document.COLUMN_DISPLAY_NAME, name);
		if (stat.isDirectory()) {
			row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
		} else if (stat.isRegularFile()) {
			row.add(Document.COLUMN_MIME_TYPE, getMime(name));
		}
		row.add(Document.COLUMN_SIZE, stat.size);
		row.add(Document.COLUMN_LAST_MODIFIED, stat.mtime * 1000);
		row.add(Document.COLUMN_FLAGS, 0);
	}

	public Cursor queryChildDocuments(
		String parentDocumentId, String[] projection, String sortOrder) {
		MatrixCursor result =
			new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v("SFTP", "qcf " + parentDocumentId + " on " + host + ":" + port);
		editPolicyIfMainThread();
		SFTPv3Client sftp = retriveConnection(result);
		if (sftp == null) {
			return result;
		}
		String filename = parentDocumentId.substring(parentDocumentId.indexOf("/") + 1);
		try {
			Vector<SFTPv3DirectoryEntry> res = sftp.ls(filename);
			for (SFTPv3DirectoryEntry entry : res) {
				if (entry.filename.equals(".") || entry.filename.equals(".."))
					continue;
				MatrixCursor.RowBuilder row = result.newRow();
				row.add(Document.COLUMN_DOCUMENT_ID,
					parentDocumentId + '/' + entry.filename);
				fillStatRow(row, entry.filename, entry.attributes);
			}
		} catch (IOException e) {
			throwOrAddErrorExtra(e.toString(), result);
		}
		sftp.close();
		return result;
	}

	public Cursor queryDocument(String documentId, String[] projection) {
		MatrixCursor result =
			new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v("SFTP", "qf " + documentId + " on " + host + ":" + port);
		editPolicyIfMainThread();
		SFTPv3Client sftp = retriveConnection(result);
		if (sftp == null) {
			return result;
		}
		String filename = documentId.substring(documentId.indexOf("/") + 1);
		String basename = filename.substring(filename.lastIndexOf("/") + 1);
		try {
			SFTPv3FileAttributes stat = sftp.stat(filename);
			MatrixCursor.RowBuilder row = result.newRow();
			row.add(Document.COLUMN_DOCUMENT_ID, documentId);
			fillStatRow(row, basename, stat);
		} catch (IOException e) {
			throwOrAddErrorExtra(e.toString(), result);
		}
		sftp.close();
		return result;
	}

	public Cursor queryRoots(String[] projection) {
		if (connection != null) {
			connection.close();
			connection = null;
		}
		MatrixCursor result =
			new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
		SharedPreferences settings =
			PreferenceManager.getDefaultSharedPreferences(getContext());
		host = settings.getString("host", "");
		port = settings.getString("port", "22");
		String mountpoint = settings.getString("mountpoint", ".");
		if (mountpoint.equals("")) mountpoint = ".";
		String title = "sftp://" + host;
		if (!port.equals("22")) {
			title += ":" + port;
		}
		if (mountpoint.startsWith("/")) {
			title += mountpoint;
		} else if (!mountpoint.equals(".")) {
			title += "/~/" + mountpoint;
		}
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Root.COLUMN_ROOT_ID, host + ":" + port);
		row.add(Root.COLUMN_DOCUMENT_ID, host + ":" + port + "/" + mountpoint);
		row.add(Root.COLUMN_FLAGS, 0);
		row.add(Root.COLUMN_TITLE, title);
		row.add(Root.COLUMN_ICON, R.mipmap.sym_def_app_icon);
		row.add(Root.COLUMN_SUMMARY, "SFTP with user: " +
			settings.getString("username", ""));
		return result;
	}
}

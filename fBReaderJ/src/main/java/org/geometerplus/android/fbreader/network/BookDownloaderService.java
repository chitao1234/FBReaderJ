/*
 * Copyright (C) 2010-2015 FBReader.ORG Limited <contact@fbreader.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader.network;

import java.util.*;
import java.io.*;

import android.app.*;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.widget.RemoteViews;
import android.widget.Toast;

import org.geometerplus.zlibrary.core.network.*;
import org.geometerplus.zlibrary.core.resources.ZLResource;
import org.geometerplus.zlibrary.core.util.MimeType;
import org.geometerplus.zlibrary.ui.android.R;
import org.geometerplus.zlibrary.ui.android.network.SQLiteCookieDatabase;

import org.geometerplus.fbreader.network.urlInfo.UrlInfo;
import org.geometerplus.fbreader.network.urlInfo.BookUrlInfo;

import org.geometerplus.android.fbreader.FBReader;
import org.geometerplus.android.fbreader.NotificationUtil;
import org.geometerplus.android.fbreader.libraryService.BookCollectionShadow;
import org.geometerplus.android.fbreader.network.auth.ServiceNetworkContext;

public class BookDownloaderService extends Service {
	private final ZLNetworkContext myNetworkContext = new ServiceNetworkContext(this);

	public interface Key {
		String FROM_SYNC = "fbreader.downloader.from.sync";
		String BOOK_TITLE = "fbreader.downloader.book.title";
		String BOOK_KIND = "fbreader.downloader.book.kind";
		String BOOK_MIME = "fbreader.downloader.book.mime";
		String CLEAN_URL = "fbreader.downloader.clean.url";
		String SHOW_NOTIFICATIONS = "fbreader.downloader.show.notifications";
	}

	public interface Notifications {
		int DOWNLOAD_STARTED = 0x0001;
		int ALREADY_IN_PROGRESS = 0x0002;

		int ALL = 0x0003;
	}

	private Set<String> myDownloadingURLs = Collections.synchronizedSet(new HashSet<String>());
	private Set<Integer> myOngoingNotifications = new HashSet<Integer>();

	private volatile int myServiceCounter;

	private void doStart() {
		++myServiceCounter;
	}

	private void doStop() {
		if (--myServiceCounter == 0) {
			stopSelf();
		}
	}

	public static ZLResource getResource() {
		return ZLResource.resource("bookDownloader");
	}

	@Override
	public IBinder onBind(Intent intent) {
		return new BookDownloaderInterface.Stub() {
			public boolean isBeingDownloaded(String url) {
				return myDownloadingURLs.contains(url);
			}
		};
	}

	@Override
	public void onDestroy() {
		for (int notificationId : myOngoingNotifications) {
			NotificationUtil.drop(this, notificationId);
		}
		myOngoingNotifications.clear();
		super.onDestroy();
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		doStart();

		final Uri uri = intent != null ? intent.getData() : null;
		if (uri == null) {
			doStop();
			return;
		}
		intent.setData(null);

		if (intent.getBooleanExtra(Key.FROM_SYNC, false)) {
			NotificationUtil.drop(this, NotificationUtil.MISSING_BOOK_ID);
		}

		final int notifications = intent.getIntExtra(Key.SHOW_NOTIFICATIONS, 0);

		final String url = uri.toString();
		final MimeType mime = MimeType.get(intent.getStringExtra(Key.BOOK_MIME));
		UrlInfo.Type referenceType = (UrlInfo.Type)intent.getSerializableExtra(Key.BOOK_KIND);
		if (referenceType == null) {
			referenceType = UrlInfo.Type.Book;
		}

		String cleanURL = intent.getStringExtra(Key.CLEAN_URL);
		if (cleanURL == null) {
			cleanURL = url;
		}

		if (myDownloadingURLs.contains(url)) {
			if ((notifications & Notifications.ALREADY_IN_PROGRESS) != 0) {
				showMessage("alreadyDownloading");
			}
			doStop();
			return;
		}

		final String fileName = BookUrlInfo.makeBookFileName(cleanURL, mime, referenceType);
		if (fileName == null) {
			doStop();
			return;
		}

		int index = fileName.lastIndexOf(File.separator);
		if (index != -1) {
			final String dir = fileName.substring(0, index);
			final File dirFile = new File(dir);
			if (!dirFile.exists() && !dirFile.mkdirs()) {
				showMessage("cannotCreateDirectory", dirFile.getPath());
				doStop();
				return;
			}
			if (!dirFile.exists() || !dirFile.isDirectory()) {
				showMessage("cannotCreateDirectory", dirFile.getPath());
				doStop();
				return;
			}
		}

		final File fileFile = new File(fileName);
		if (fileFile.exists()) {
			if (!fileFile.isFile()) {
				showMessage("cannotCreateFile", fileFile.getPath());
				doStop();
				return;
			}
			// TODO: question box: redownload?
			doStop();
			startActivity(getFBReaderIntent(fileFile));
			return;
		}
		String title = intent.getStringExtra(Key.BOOK_TITLE);
		if (title == null || title.length() == 0) {
			title = fileFile.getName();
		}
		if ((notifications & Notifications.DOWNLOAD_STARTED) != 0) {
			showMessage("downloadStarted");
		}
		startFileDownload(url, fileFile, title);
	}

	private void showMessageText(String text) {
		Toast.makeText(
			getApplicationContext(),
			text,
			Toast.LENGTH_LONG
		).show();
	}

	private void showMessage(String key) {
		showMessageText(getResource().getResource(key).getValue());
	}

	private void showMessage(String key, String parameter) {
		showMessageText(getResource().getResource(key).getValue().replace("%s", parameter));
	}

	private Intent getFBReaderIntent(final File file) {
		final Intent intent = new Intent(getApplicationContext(), FBReader.class);
		if (file != null) {
			intent.setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file));
		}
		return intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
	}

	// from AOSP
	private static void setLatestEventInfo(Notification notification, android.content.Context context, CharSequence contentTitle, CharSequence contentText, PendingIntent contentIntent) {
		if (context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N) {
			notification.extras.putBoolean(Notification.EXTRA_SHOW_WHEN, true);
		}

		// ensure that any information already set directly is preserved
		final Notification.Builder builder = new Notification.Builder(context);

		// start constructing the builder
		if (notification.actions != null) {
			for (Notification.Action action : notification.actions) {
				builder.addAction(action);
			}
		}

		if (notification.extras.containsKey(Notification.EXTRA_PEOPLE)) {
			for (String person: notification.extras.getStringArray(Notification.EXTRA_PEOPLE)) {
				builder.addPerson(person);
			}
		}

		if (notification.getSmallIcon() == null && notification.icon != 0) {
			builder.setSmallIcon(notification.icon);
		}

		if (notification.getLargeIcon() == null && notification.largeIcon != null) {
			builder.setLargeIcon(notification.largeIcon);
		}

		String templateClass = notification.extras.getString(Notification.EXTRA_TEMPLATE);
		if (!android.text.TextUtils.isEmpty(templateClass)) {
			final Class<? extends Notification.Style> styleClass
					= getNotificationStyleClass(templateClass);
			if (styleClass != null) {
				try {
					final java.lang.reflect.Constructor<? extends Notification.Style> ctor =
							styleClass.getDeclaredConstructor();
					ctor.setAccessible(true);
					final Notification.Style style = ctor.newInstance();
					restoreFromExtras(style, notification.extras);

					if (style != null) {
						builder.setStyle(style);
					}
				} catch (Throwable ignore) {
				}
			}
		}
		// end constructing the builder

		// now apply the latestEventInfo fields
		if (contentTitle != null) {
			builder.setContentTitle(contentTitle);
		}
		if (contentText != null) {
			builder.setContentText(contentText);
		}
		builder.setContentIntent(contentIntent);

		builder.build(); // callers expect this notification to be ready to use
	}

	// from AOSP
	private static Class<? extends Notification.Style> getNotificationStyleClass(String templateClass) {
		Class<? extends Notification.Style>[] classes = new Class[] {
				Notification.BigTextStyle.class, Notification.BigPictureStyle.class,
				Notification.InboxStyle.class, Notification.MediaStyle.class,
				Notification.DecoratedCustomViewStyle.class,
				Notification.DecoratedMediaCustomViewStyle.class,
				Notification.MessagingStyle.class };
		for (Class<? extends Notification.Style> innerClass : classes) {
			if (templateClass.equals(innerClass.getName())) {
				return innerClass;
			}
		}
		return null;
	}

	// from AOSP
	private static void restoreFromExtras(Notification.Style style, Bundle extras) {
		try {
			if (extras.containsKey(Notification.EXTRA_SUMMARY_TEXT)) {
				Notification.Style.class.getDeclaredMethod("internalSetSummaryText", CharSequence.class).invoke(
						style,
						extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
				);
			}
			if (extras.containsKey(Notification.EXTRA_TITLE_BIG)) {
				Notification.Style.class.getDeclaredMethod("internalSetBigContentTitle", CharSequence.class).invoke(
						style,
						extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
				);
			}
		} catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException ignore) {
		}
	}
	
	private Notification createDownloadFinishNotification(File file, String title, boolean success) {
		final ZLResource resource = getResource();
		final String tickerText = success ?
			resource.getResource("downloadCompleteTicker").getValue() :
			resource.getResource("downloadFailedTicker").getValue();
		final String contentText = success ?
			resource.getResource("downloadComplete").getValue() :
			resource.getResource("downloadFailed").getValue();
		final Notification notification = new Notification(
			android.R.drawable.stat_sys_download_done,
			tickerText,
			System.currentTimeMillis()
		);
		notification.flags |= Notification.FLAG_AUTO_CANCEL;
		final Intent intent = success ? getFBReaderIntent(file) : new Intent();
		final PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, 0);
		setLatestEventInfo(notification, getApplicationContext(), title, contentText, contentIntent);
		return notification;
	}

	private Notification createDownloadProgressNotification(String title) {
		final RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.download_notification);
		contentView.setTextViewText(R.id.download_notification_title, title);
		contentView.setTextViewText(R.id.download_notification_progress_text, "");
		contentView.setProgressBar(R.id.download_notification_progress_bar, 100, 0, true);

		final PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(), 0);

		final Notification notification = new Notification();
		notification.icon = android.R.drawable.stat_sys_download;
		notification.largeIcon = BitmapFactory.decodeResource(getResources(), R.drawable.fbreader);
		notification.flags |= Notification.FLAG_ONGOING_EVENT;
		notification.contentView = contentView;
		notification.contentIntent = contentIntent;

		return notification;
	}

	private void sendDownloaderCallback() {
		sendBroadcast(
			new Intent(getApplicationContext(), ListenerCallback.class)
		);
	}

	private void startFileDownload(final String urlString, final File file, final String title) {
		myDownloadingURLs.add(urlString);
		sendDownloaderCallback();

		final int notificationId = NotificationUtil.getDownloadId(file.getPath());
		final Notification progressNotification = createDownloadProgressNotification(title);

		final NotificationManager notificationManager =
			(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		myOngoingNotifications.add(Integer.valueOf(notificationId));
		notificationManager.notify(notificationId, progressNotification);

		final int MESSAGE_PROGRESS = 0;
		final int MESSAGE_FINISH = 1;

		final Handler handler = new Handler() {
			public void handleMessage(Message message) {
				switch (message.what) {
					case MESSAGE_PROGRESS:
					{
						final int progress = message.arg1;
						final RemoteViews contentView = (RemoteViews)progressNotification.contentView;
						final boolean showPercent = progress >= 0;
						contentView.setTextViewText(
							R.id.download_notification_progress_text,
							showPercent ? progress + "%" : ""
						);
						contentView.setProgressBar(
							R.id.download_notification_progress_bar,
							100, showPercent ? progress : 0, !showPercent
						);
						notificationManager.notify(notificationId, progressNotification);
						break;
					}
					case MESSAGE_FINISH:
						myDownloadingURLs.remove(urlString);
						NotificationUtil.drop(BookDownloaderService.this, notificationId);
						myOngoingNotifications.remove(Integer.valueOf(notificationId));
						notificationManager.notify(
							notificationId,
							createDownloadFinishNotification(file, title, message.arg1 != 0)
						);
						sendDownloaderCallback();
						doStop();
						break;
				}
			}
		};

		final ZLNetworkRequest request = new ZLNetworkRequest.Get(urlString) {
			public void handleStream(InputStream inputStream, int length) throws IOException, ZLNetworkException {
				final int updateIntervalMillis = 1000; // FIXME: remove hardcoded time constant

				int downloadedPart = 0;
				long progressTime = System.currentTimeMillis() + updateIntervalMillis;
				if (length <= 0) {
					handler.sendMessage(handler.obtainMessage(MESSAGE_PROGRESS, -1, 0, null));
				}
				final OutputStream outStream;
				try {
					outStream = new FileOutputStream(file);
				} catch (FileNotFoundException ex) {
					throw ZLNetworkException.forCode(ZLNetworkException.ERROR_CREATE_FILE, file.getPath());
				}
				try {
					final byte[] buffer = new byte[8192];
					while (true) {
						final int size = inputStream.read(buffer);
						if (size <= 0) {
							break;
						}
						downloadedPart += size;
						if (length > 0) {
							final long currentTime = System.currentTimeMillis();
							if (currentTime > progressTime) {
								progressTime = currentTime + updateIntervalMillis;
								handler.sendMessage(handler.obtainMessage(
									MESSAGE_PROGRESS, downloadedPart * 100 / length, 0, null
								));
							}
						}
						outStream.write(buffer, 0, size);
					}
					final BookCollectionShadow collection = new BookCollectionShadow();
					collection.bindToService(BookDownloaderService.this, new Runnable() {
						@Override
						public void run() {
							collection.rescan(file.getPath());
							collection.unbind();
						}
					});
				} finally {
					outStream.close();
				}
			}
		};

		final Thread downloader = new Thread(new Runnable() {
			public void run() {
				boolean success = false;
				try {
					SQLiteCookieDatabase.init(BookDownloaderService.this);
					myNetworkContext.perform(request);
					success = true;
				} catch (final ZLNetworkException e) {
					e.printStackTrace();
					final String title = getResource().getResource("downloadFailed").getValue();
					handler.post(new Runnable() {
						public void run() {
							showMessageText(title + ": " + e.getMessage());
						}
					});
					file.delete();
				} finally {
					handler.sendMessage(handler.obtainMessage(
						MESSAGE_FINISH, success ? 1 : 0, 0, null
					));
				}
			}
		});
		downloader.setPriority(Thread.MIN_PRIORITY);
		downloader.start();
	}
}

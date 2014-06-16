package com.mapzen.core;

import com.mapzen.MapzenApplication;
import com.mapzen.util.DatabaseHelper;
import com.mapzen.util.Logger;

import org.apache.http.Header;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.os.AsyncTask;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import static com.mapzen.util.DatabaseHelper.COLUMN_ALT;
import static com.mapzen.util.DatabaseHelper.COLUMN_LAT;
import static com.mapzen.util.DatabaseHelper.COLUMN_LNG;
import static com.mapzen.util.DatabaseHelper.COLUMN_READY_FOR_UPLOAD;
import static com.mapzen.util.DatabaseHelper.COLUMN_ROUTE_ID;
import static com.mapzen.util.DatabaseHelper.COLUMN_TABLE_ID;
import static com.mapzen.util.DatabaseHelper.COLUMN_TIME;
import static com.mapzen.util.DatabaseHelper.COLUMN_MSG;
import static com.mapzen.util.DatabaseHelper.COLUMN_UPLOADED;
import static com.mapzen.util.DatabaseHelper.TABLE_LOCATIONS;
import static com.mapzen.util.DatabaseHelper.TABLE_ROUTES;

import static javax.xml.transform.OutputKeys.ENCODING;
import static javax.xml.transform.OutputKeys.INDENT;
import static javax.xml.transform.OutputKeys.METHOD;
import static javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION;
import static javax.xml.transform.OutputKeys.VERSION;

import static org.apache.http.protocol.HTTP.UTF_8;
import static org.scribe.model.Verb.GET;
import static org.scribe.model.Verb.POST;

public class DataUploadService extends Service {
    public static final int MIN_NUM_TRACKING_POINTS = 10;
    private MapzenApplication app;

    @Override
    public void onCreate() {
        super.onCreate();
        app = (MapzenApplication) getApplication();
        Logger.d("DataUploadService: oncreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d("DataUploadService: onStartCommand");
        (new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                checkForUploadPermission();
                Cursor cursor = null;
                try {
                    cursor = app.getDb().query(
                            TABLE_ROUTES,
                            new String[] { COLUMN_TABLE_ID, COLUMN_MSG },
                            COLUMN_UPLOADED + " is null AND " + COLUMN_READY_FOR_UPLOAD + " == ?",
                            new String[] { "1" }, null, null, null);
                    while (cursor.moveToNext()) {
                        int routeIdIndex = cursor.getColumnIndex(COLUMN_TABLE_ID);
                        int routeDescription = cursor.getColumnIndex(COLUMN_MSG);
                        generateGpxXmlFor(cursor.getString(routeIdIndex),
                                cursor.getString(routeDescription));
                    }
                } catch (SQLiteDatabaseLockedException exception) {
                    Logger.d("DataUpload: database is locked lets try again later");
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                return null;
            }
        }).execute();
        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public DataUploadService() {
        Logger.d("DataUploadService: constructor");
    }

    public void generateGpxXmlFor(String routeId, String description) {
        if (app.getAccessToken() == null) {
            Logger.d("DataUploadService: user not logged into OSM");
            return;
        }
        Logger.d("DataUpload: generating for " + routeId);
        ByteArrayOutputStream output = null;
        try {
            DOMSource domSource = getDocument(routeId);
            if (domSource == null) {
                Logger.d("There are 0 tracking points");
                setRouteAsUploaded(routeId);
                return;
            }
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            setOutputFormat(transformer);
            output = new ByteArrayOutputStream();
            StreamResult result = new StreamResult(output);
            transformer.transform(domSource, result);
        } catch (TransformerException e) {
            Logger.e("Transforming failed: " + e.getMessage());
        }
        Logger.d("DataUpload gonna write " + description);
        submitCompressedFile(output, routeId, description);
    }

    private void submitCompressedFile(ByteArrayOutputStream output,
                                      String routeId, String description) {
        Logger.d("DataUpload gonna submit");
        try {
            String gpxString = output.toString();
            byte[] compressedGPX = compressGPX(gpxString);
            submitTrace(description, routeId, compressedGPX);

        } catch (IOException e) {
            Logger.e("IOException occurred " + e.getMessage());
        }
    }

    private DOMSource getDocument(String routeId) {
        DOMSource domSource = null;
        try {
            DateTimeFormatter isoDateParser = ISODateTimeFormat.dateTimeNoMillis();
            Cursor cursor = app.getDb().query(TABLE_LOCATIONS,
                    new String[] { COLUMN_LAT, COLUMN_LNG, COLUMN_ALT, COLUMN_TIME },
                    COLUMN_ROUTE_ID + " = ?",
                    new String[] { routeId }, null, null, null);
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory
                    .newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory
                    .newDocumentBuilder();
            Document document = documentBuilder.newDocument();
            Element rootElement = getRootElement(document);
            document.appendChild(rootElement);
            Element trkElement = document.createElement("trk");
            rootElement.appendChild(trkElement);
            Element nameElement = document.createElement("name");
            nameElement.setTextContent("Mapzen Route");
            trkElement.appendChild(nameElement);
            Element trksegElement = document.createElement("trkseg");
            while (cursor.moveToNext()) {
                addTrackPoint(isoDateParser, cursor, document, trksegElement);
            }
            trkElement.appendChild(trksegElement);
            Element documentElement =  document.getDocumentElement();
            int numberOfPoints = documentElement.getElementsByTagName("ele").getLength();
            if (numberOfPoints < MIN_NUM_TRACKING_POINTS) {
                return null;
            }
            domSource = new DOMSource(documentElement);
        } catch (ParserConfigurationException e) {
            Logger.e("Building xml failed: " + e.getMessage());
        }
      return domSource;
    }

    private void setOutputFormat(Transformer transformer) {
        Properties outFormat = new Properties();
        outFormat.setProperty(INDENT, "yes");
        outFormat.setProperty(METHOD, "xml");
        outFormat.setProperty(OMIT_XML_DECLARATION, "no");
        outFormat.setProperty(VERSION, "1.0");
        outFormat.setProperty(ENCODING, UTF_8);
        transformer.setOutputProperties(outFormat);
    }

    private void addTrackPoint(DateTimeFormatter isoDateParser, Cursor cursor, Document document,
            Element trksegElement) {
        int latIndex = cursor.getColumnIndex(COLUMN_LAT);
        int lonIndex = cursor.getColumnIndex(COLUMN_LNG);
        int altIndex = cursor.getColumnIndex(COLUMN_ALT);
        int timeIndex = cursor.getColumnIndex(COLUMN_TIME);
        Element trkptElement = document.createElement("trkpt");
        Element elevationElement = document.createElement("ele");
        Element timeElement = document.createElement("time");
        trkptElement.setAttribute("lat", cursor.getString(latIndex));
        trkptElement.setAttribute("lon", cursor.getString(lonIndex));
        elevationElement.setTextContent(cursor.getString(altIndex));

        DateTime date = new DateTime(cursor.getLong(timeIndex));
        timeElement.setTextContent(date.toString(isoDateParser));

        trkptElement.appendChild(elevationElement);
        trkptElement.appendChild(timeElement);
        trksegElement.appendChild(trkptElement);
    }

    private Element getRootElement(Document document) {
        Element rootElement = document.createElement("gpx");
        rootElement.setAttribute("version", "1.0");
        rootElement.setAttribute("creator", "mapzen - start where you are http://mazpen.com");
        rootElement.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
        rootElement.setAttribute("xmlns:schemaLocation",
                "http://www.topografix.com/GPX/1/0 http://www.topografix.com/GPX/1/0/gpx.xsd");
        rootElement.setAttribute("xmlns", "http://www.topografix.com/GPX/1/0");
        return rootElement;
    }

    public OAuthRequest getOAuthRequest() {
        OAuthRequest request =
                new OAuthRequest(POST, OSMApi.BASE_URL + OSMApi.CREATE_GPX);

        return request;
    }

    public OAuthRequest getPermissionsRequest() {
        OAuthRequest request =
                new OAuthRequest(GET, OSMApi.BASE_URL + OSMApi.CHECK_PERMISSIONS);
        return request;
    }

    public void submitTrace(String description, String routeId, byte[] compressedGPX) {
        Logger.d("DataUpload submitting trace");

        MultipartEntity reqEntity = new MultipartEntity();
        try {
            reqEntity.addPart("description", new StringBody(description));
            reqEntity.addPart("visibility", new StringBody("private"));
            reqEntity.addPart("public", new StringBody("0"));
        } catch (UnsupportedEncodingException e) {
            Logger.e(e.getMessage());
        }

        reqEntity.addPart("file", new ByteArrayBody(compressedGPX, routeId + ".gpx.gz"));

        ByteArrayOutputStream bos =
                new ByteArrayOutputStream((int) reqEntity.getContentLength());

        try {
            reqEntity.writeTo(bos);
        } catch (IOException e) {
            Logger.e("IOException: " + e.getMessage());
        }

        OAuthRequest request = getOAuthRequest();
        request.addPayload(bos.toByteArray());
        Header contentType = reqEntity.getContentType();
        request.addHeader(contentType.getName(), contentType.getValue());

        app.getOsmOauthService().signRequest(app.getAccessToken(), request);
        Response response = request.send();

        Logger.d("DataUpload Response:" + response.getBody());
        if (response.isSuccessful()) {
            setRouteAsUploaded(routeId);
            Logger.d("DataUpload: done uploading: " + routeId);
        }
    }

    public void checkForUploadPermission() {
        try {
            String writePermission = "<permission name=\"allow_write_gpx\"/>";
            OAuthRequest request = getPermissionsRequest();
            app.getOsmOauthService().signRequest(app.getAccessToken(), request);
            Response response = request.send();
            if (!response.getBody().contains(writePermission)) {
                stopSelf();
            }
        } catch (Exception e) {
            Logger.d(e.toString());
        }
    }

    public void setRouteAsUploaded(String routeId) {
        ContentValues cv = new ContentValues();
        cv.put(DatabaseHelper.COLUMN_UPLOADED, 1);
        app.getDb().update(TABLE_ROUTES, cv, COLUMN_TABLE_ID + " = ?",
                new String[]{routeId});
    }

    public static byte[] compressGPX(String gpxString) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(gpxString.length());
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(gpxString.getBytes());
        gos.close();
        byte[] compressedGPX = os.toByteArray();
        os.close();
        return compressedGPX;
    }

}

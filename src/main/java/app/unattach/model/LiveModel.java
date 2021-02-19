package app.unattach.model;

import app.unattach.controller.LongTask;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.*;
import java.lang.Thread;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.apache.commons.codec.binary.Base64.*;

public class LiveModel implements Model {
  private static final Logger LOGGER = Logger.getLogger(LiveModel.class.getName());
  private static final String USER = "me";

  private final Config config;
  private GmailServiceLifecycleManager serviceLifecycleManager;
  private Gmail service;
  private Session session;
  private List<Email> emails;
  private String emailAddress;

  public LiveModel() {
    this.config = new FileConfig();
    configureMimeLibrary();
    reset();
  }

  private void configureMimeLibrary() {
    // see http://docs.oracle.com/javaee/6/api/javax/mail/internet/package-summary.html
    allowEmptyPartsInEmails();
    allowNonConformingEmailHeaders();
    // see https://stackoverflow.com/a/5292975/974531
    disablePrivateFetch();
    // see https://community.oracle.com/thread/1590013?start=0&tstart=0
    enableIgnoreErrors();
  }

  private void allowEmptyPartsInEmails() {
    System.setProperty("mail.mime.multipart.allowempty", "true");
  }

  private void allowNonConformingEmailHeaders() {
    System.setProperty("mail.mime.parameters.strict", "false");
  }

  private void disablePrivateFetch() {
    System.setProperty("mail.imaps.partialfetch", "false");
  }

  private void enableIgnoreErrors() {
    System.setProperty("mail.mime.base64.ignoreerrors", "true");
  }

  private void reset() {
    serviceLifecycleManager = null;
    service = null;
    emailAddress = null;
    clearPreviousSearch();
  }

  @Override
  public void clearPreviousSearch() {
    emails = new ArrayList<>();
  }

  @Override
  public DefaultArtifactVersion getLatestVersion() throws IOException, InterruptedException {
    return HttpClient.getLatestVersion();
  }

  @Override
  public void signIn() throws IOException, GeneralSecurityException {
    configureService();
    try {
      // Test call to the service. This can fail due to token issues.
      getEmailAddress();
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Initial signing in failed. Explicitly signing out and retrying..", e);
      signOut();
      configureService();
    }
  }

  private void configureService() throws GeneralSecurityException, IOException {
    serviceLifecycleManager = new GmailServiceLifecycleManager();
    // 250 quota units / user / second
    // each set of requests should assume they start with clean quota
    service = serviceLifecycleManager.signIn();
    Properties props = new Properties();
    session = Session.getInstance(props);
  }

  @Override
  public void signOut() throws IOException {
    serviceLifecycleManager.signOut();
    reset();
  }

  @Override
  public void sendToServer(String contentDescription, String userEmail, String stackTraceText, String userText)
      throws IOException, InterruptedException {
    HttpClient.sendToServer(contentDescription, userEmail, stackTraceText, userText);
  }

  @Override
  public void subscribe(String emailAddress) throws IOException, InterruptedException {
    HttpClient.subscribe(emailAddress);
  }

  @Override
  public String getEmailAddress() throws IOException {
    if (emailAddress == null) {
      // unknown quota units
      Profile profile = service.users().getProfile(USER).setFields("emailAddress").execute();
      emailAddress = profile.getEmailAddress();
    }
    return emailAddress;
  }

  @Override
  public List<Email> getEmails() {
    return emails;
  }

  @Override
  public LongTask<ProcessEmailResult> getProcessTask(Email email, ProcessSettings processSettings) {
    return new ProcessEmailTask(email, e -> processEmail(e, processSettings) /* 40 quota units */);
  }

  private ProcessEmailResult processEmail(Email email, ProcessSettings processSettings)
      throws IOException, MessagingException {
    Message message = getRawMessage(email.getGmailId()); // 5 quota units
    MimeMessage mimeMessage = getMimeMessage(message);
    String newUniqueId = null;
    if (processSettings.processOption.shouldBackup()) {
      backupEmail(email, processSettings, mimeMessage);
    }
    Set<String> fileNames = EmailProcessor.process(email, mimeMessage, processSettings);
    if (processSettings.processOption.shouldDownload() && !processSettings.processOption.shouldRemove()) {
      addLabel(message.getId(), processSettings.processOption.getDownloadedLabelId());
    }
    if (processSettings.processOption.shouldRemove() && !fileNames.isEmpty()) {
      updateRawMessage(message, mimeMessage);
      Message newMessage = insertSlimMessage(message); // 25 quota units
      newMessage = getMetadataForNewMessage(newMessage); // 5 quota units
      Map<String, String> headerMap = getHeaderMap(newMessage);
      newUniqueId = headerMap.get("message-id");
      if (processSettings.processOption.shouldDownload()) {
        addLabel(newMessage.getId(), processSettings.processOption.getDownloadedLabelId());
      }
      addLabel(newMessage.getId(), processSettings.processOption.getRemovedLabelId());
      addLabel(newMessage.getId(), "STARRED");
      removeOriginalMessage(processSettings.processOption.shouldDeleteOriginal(), message.getId()); // 5-10 quota units
    }
    return new ProcessEmailResult(newUniqueId, fileNames);
  }

  private Message getRawMessage(String emailId) throws IOException {
    // 1 messages.get == 5 quota units
    // download limit = 2500 MB / day / user
    return service.users().messages().get(USER, emailId).setFormat("raw").execute();
  }

  private MimeMessage getMimeMessage(Message message) throws MessagingException, IOException {
    String rawBefore = message.getRaw();
    if (rawBefore == null) {
      throw new IOException("Unable to extract the contents of the email.");
    }
    byte[] emailBytes = decodeBase64(rawBefore);
    try (InputStream is = new ByteArrayInputStream(emailBytes)) {
      return new MimeMessage(session, is);
    }
  }

  private void backupEmail(Email email, ProcessSettings processSettings, MimeMessage mimeMessage)
          throws IOException, MessagingException {
    //noinspection ResultOfMethodCallIgnored
    processSettings.targetDirectory.mkdirs();
    String filename = email.getGmailId() + ".eml";
    try (OutputStream os = new FileOutputStream(new File(processSettings.targetDirectory, filename))) {
      mimeMessage.writeTo(os);
    }
  }

  private void updateRawMessage(Message message, MimeMessage mimeMessage) throws IOException, MessagingException {
    try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
      mimeMessage.writeTo(buffer);
      String raw = encodeBase64URLSafeString(buffer.toByteArray());
      message.setRaw(raw);
    }
  }

  private void addLabel(String emailId, String labelId) throws IOException {
    if (labelId == null) {
      LOGGER.log(Level.WARNING, "Cannot add a label, because it was not specified.");
      return;
    }
    ModifyMessageRequest modifyMessageRequest = new ModifyMessageRequest();
    modifyMessageRequest.setAddLabelIds(Collections.singletonList(labelId));
    // 1 messages.modify == 5 quota units
    service.users().messages().modify(USER, emailId, modifyMessageRequest).execute();
  }

  private Message insertSlimMessage(Message message) throws IOException {
    // 1 messages.insert == 25 quota units
    // upload limit = 500 MB / day / user
    return service.users().messages().insert(USER, message).setInternalDateSource("dateHeader").execute();
  }

  private Message getMetadataForNewMessage(Message newMessage) throws IOException {
    // 1 messages.get == 5 quota units
    return service.users().messages().get(LiveModel.USER, newMessage.getId()).setFields("id,payload/headers").execute();
  }

  private void removeOriginalMessage(boolean deleteOriginal, String emailId) throws IOException {
    if (deleteOriginal) {
      // 1 messages.delete == 10 quota units
      service.users().messages().delete(USER, emailId).execute();
    } else {
      // 1 messages.trash == 5 quota units
      service.users().messages().trash(USER, emailId).execute();
    }
  }

  @Override
  public GetEmailMetadataTask getSearchTask(String query) throws IOException, InterruptedException {
    List<String> emailIdsToProcess = getEmailIds(query).stream().map(Message::getId).collect(Collectors.toList());

    JsonBatchCallback<Message> perEmailCallback = new JsonBatchCallback<>() {
      @Override
      public void onFailure(GoogleJsonError googleJsonError, HttpHeaders httpHeaders) throws IOException {
        throw new IOException(googleJsonError.getMessage());
      }

      @Override
      public void onSuccess(Message message, HttpHeaders httpHeaders) {
        Map<String, String> headerMap = getHeaderMap(message);
        String emailId = message.getId();
        String uniqueId = headerMap.get("message-id");
        List<String> labelIds = message.getLabelIds();
        String from = headerMap.get("from");
        String to = headerMap.get("to");
        String subject = headerMap.get("subject");
        long timestamp = message.getInternalDate();
        List<MessagePart> messageParts = message.getPayload().getParts();
        if (messageParts != null) { // Means, this is not a blank message
          List<String> attachments = messageParts.stream()
                  .map(MessagePart::getFilename).filter(StringUtils::isNotBlank).collect(Collectors.toList());
          Email email = new Email(emailId, uniqueId, labelIds, from, to, subject, timestamp, message.getSizeEstimate(),
                  attachments);
          emails.add(email);
        }
        else {
          LOGGER.log(Level.WARNING, "Skipping message as GMail returned no parts:\n" +
                  "\tGMail-ID: " + emailId + "\n" +
                  "\tMessage-ID: " + uniqueId + "\n" +
                  "\tFrom: " + from + "\n" +
                  "\tTo: " + to + "\n" +
                  "\tSubject: " + subject + "\n" +
                  "\tDate: " + new Date(timestamp));
        }
      }
    };

    return new GetEmailMetadataTask(
        emailIdsToProcess,
        (startIndexInclusive, endIndexExclusive) -> {
          BatchRequest batch = service.batch();
          for (int emailIndex = startIndexInclusive; emailIndex < endIndexExclusive; ++emailIndex) {
            getEmailMetadata(service, emailIdsToProcess.get(emailIndex), batch, perEmailCallback);
          }
          batch.execute();
        }
    );
  }

  private List<Message> getEmailIds(String query) throws IOException, InterruptedException {
    List<Message> messages = new ArrayList<>();
    String pageToken = null;
    do {
      // 1 messages.list == 5 quota units
      Gmail.Users.Messages.List request = service.users().messages().list(USER).setFields("messages/id").setQ(query)
          .setMaxResults(100000L).setPageToken(pageToken);
      ListMessagesResponse response = request.execute();
      if (response == null) {
        break;
      }
      List<Message> responseMessages = response.getMessages();
      if (responseMessages == null) {
        break;
      }
      messages.addAll(responseMessages);
      pageToken = response.getNextPageToken();
      Thread.sleep(25);
    } while (pageToken != null);
    return messages;
  }

  @Override
  public SortedMap<String, String> getIdToLabel() throws IOException {
    // 1 labels.get == 1 quota unit
    ListLabelsResponse response = service.users().labels().list(USER).setFields("labels/id,labels/name").execute();
    SortedMap<String, String> labelToId = new TreeMap<>();
    for (Label label : response.getLabels()) {
      labelToId.put(label.getId(), label.getName());
    }
    return labelToId;
  }

  @Override
  public String createLabel(String name) throws IOException {
    Label labelIn = new Label();
    labelIn.setName(name);
    labelIn.setLabelListVisibility("labelShow");
    labelIn.setMessageListVisibility("show");
    LabelColor labelColor = new LabelColor();
    labelColor.setBackgroundColor("#ffffff");
    labelColor.setTextColor("#fb4c2f");
    labelIn.setColor(labelColor);
    Label labelOut = service.users().labels().create(USER, labelIn).execute();
    return labelOut.getId();
  }

  @Override
  public Config getConfig() {
    return config;
  }

  private static void getEmailMetadata(Gmail service, String messageId, BatchRequest batch,
                                       JsonBatchCallback<Message> callback) throws IOException {
    // 1 messages.get == 5 quota units
    String fields = "id,labelIds,internalDate,payload/parts/filename,payload/headers,sizeEstimate";
    service.users().messages().get(LiveModel.USER, messageId).setFields(fields).queue(batch, callback);
  }

  private static Map<String, String> getHeaderMap(Message message) {
    List<MessagePartHeader> headers = message.getPayload().getHeaders();
    Map<String, String> headerMap = new HashMap<>(headers.size());
    for (MessagePartHeader header : headers) {
      headerMap.put(header.getName().toLowerCase(), header.getValue());
    }
    return headerMap;
  }
}

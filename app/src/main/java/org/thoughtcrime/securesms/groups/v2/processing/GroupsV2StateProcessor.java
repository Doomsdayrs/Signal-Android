package org.thoughtcrime.securesms.groups.v2.processing;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.GroupSecretParams;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupDoesNotExistException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupMutation;
import org.thoughtcrime.securesms.groups.GroupNotAMemberException;
import org.thoughtcrime.securesms.groups.GroupProtoUtil;
import org.thoughtcrime.securesms.groups.GroupsV2Authorization;
import org.thoughtcrime.securesms.groups.v2.ProfileKeySet;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobs.AvatarGroupsV2DownloadJob;
import org.thoughtcrime.securesms.jobs.RequestGroupV2InfoJob;
import org.thoughtcrime.securesms.jobs.RetrieveProfileJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sms.IncomingGroupUpdateMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupHistoryEntry;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.groupsv2.GroupHistoryPage;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;
import org.whispersystems.signalservice.api.groupsv2.NotAbleToApplyGroupV2ChangeException;
import org.whispersystems.signalservice.api.groupsv2.PartialDecryptedGroup;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.push.exceptions.GroupNotFoundException;
import org.whispersystems.signalservice.internal.push.exceptions.NotInGroupException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Advances a groups state to a specified revision.
 */
public final class GroupsV2StateProcessor {

  private static final String TAG = Log.tag(GroupsV2StateProcessor.class);

  public static final int LATEST = GroupStateMapper.LATEST;

  /**
   * Used to mark a group state as a placeholder when there is partial knowledge (title and avater)
   * gathered from a group join link.
   */
  public static final int PLACEHOLDER_REVISION = GroupStateMapper.PLACEHOLDER_REVISION;

  /**
   * Used to mark a group state as a placeholder when you have no knowledge at all of the group
   * e.g. from a group master key from a storage service restore.
   */
  public static final int RESTORE_PLACEHOLDER_REVISION = GroupStateMapper.RESTORE_PLACEHOLDER_REVISION;

  private final Context               context;
  private final RecipientDatabase     recipientDatabase;
  private final GroupDatabase         groupDatabase;
  private final GroupsV2Authorization groupsV2Authorization;
  private final GroupsV2Api           groupsV2Api;

  public GroupsV2StateProcessor(@NonNull Context context) {
    this.context               = context.getApplicationContext();
    this.groupsV2Authorization = ApplicationDependencies.getGroupsV2Authorization();
    this.groupsV2Api           = ApplicationDependencies.getSignalServiceAccountManager().getGroupsV2Api();
    this.recipientDatabase     = SignalDatabase.recipients();
    this.groupDatabase         = SignalDatabase.groups();
  }

  public StateProcessorForGroup forGroup(@NonNull GroupMasterKey groupMasterKey) {
    ACI                     selfAci                 = SignalStore.account().requireAci();
    ProfileAndMessageHelper profileAndMessageHelper = new ProfileAndMessageHelper(context, selfAci, groupMasterKey, GroupId.v2(groupMasterKey), recipientDatabase);

    return new StateProcessorForGroup(selfAci, context, groupDatabase, groupsV2Api, groupsV2Authorization, groupMasterKey, profileAndMessageHelper);
  }

  public enum GroupState {
    /**
     * The message revision was inconsistent with server revision, should ignore
     */
    INCONSISTENT,

    /**
     * The local group was successfully updated to be consistent with the message revision
     */
    GROUP_UPDATED,

    /**
     * The local group is already consistent with the message revision or is ahead of the message revision
     */
    GROUP_CONSISTENT_OR_AHEAD
  }

  public static class GroupUpdateResult {
    private final GroupState     groupState;
    private final DecryptedGroup latestServer;

    GroupUpdateResult(@NonNull GroupState groupState, @Nullable DecryptedGroup latestServer) {
      this.groupState   = groupState;
      this.latestServer = latestServer;
    }

    public GroupState getGroupState() {
      return groupState;
    }

    public @Nullable DecryptedGroup getLatestServer() {
      return latestServer;
    }
  }

  public static final class StateProcessorForGroup {
    private final ACI                     selfAci;
    private final Context                 context;
    private final GroupDatabase           groupDatabase;
    private final GroupsV2Api             groupsV2Api;
    private final GroupsV2Authorization   groupsV2Authorization;
    private final GroupMasterKey          masterKey;
    private final GroupId.V2              groupId;
    private final GroupSecretParams       groupSecretParams;
    private final ProfileAndMessageHelper profileAndMessageHelper;

    @VisibleForTesting StateProcessorForGroup(@NonNull ACI selfAci,
                                              @NonNull Context context,
                                              @NonNull GroupDatabase groupDatabase,
                                              @NonNull GroupsV2Api groupsV2Api,
                                              @NonNull GroupsV2Authorization groupsV2Authorization,
                                              @NonNull GroupMasterKey groupMasterKey,
                                              @NonNull ProfileAndMessageHelper profileAndMessageHelper)
    {
      this.selfAci                 = selfAci;
      this.context                 = context;
      this.groupDatabase           = groupDatabase;
      this.groupsV2Api             = groupsV2Api;
      this.groupsV2Authorization   = groupsV2Authorization;
      this.masterKey               = groupMasterKey;
      this.groupId                 = GroupId.v2(masterKey);
      this.groupSecretParams       = GroupSecretParams.deriveFromMasterKey(groupMasterKey);
      this.profileAndMessageHelper = profileAndMessageHelper;
    }

    /**
     * Using network where required, will attempt to bring the local copy of the group up to the revision specified.
     *
     * @param revision use {@link #LATEST} to get latest.
     */
    @WorkerThread
    public GroupUpdateResult updateLocalGroupToRevision(final int revision,
                                                        final long timestamp,
                                                        @Nullable DecryptedGroupChange signedGroupChange)
        throws IOException, GroupNotAMemberException
    {
      if (localIsAtLeast(revision)) {
        return new GroupUpdateResult(GroupState.GROUP_CONSISTENT_OR_AHEAD, null);
      }

      GlobalGroupState inputGroupState = null;

      Optional<GroupRecord> localRecord = groupDatabase.getGroup(groupId);
      DecryptedGroup        localState  = localRecord.transform(g -> g.requireV2GroupProperties().getDecryptedGroup()).orNull();

      if (signedGroupChange != null &&
          localState != null &&
          localState.getRevision() + 1 == signedGroupChange.getRevision() &&
          revision == signedGroupChange.getRevision())
      {

        if (notInGroupAndNotBeingAdded(localRecord, signedGroupChange)) {
          Log.w(TAG, "Ignoring P2P group change because we're not currently in the group and this change doesn't add us in. Falling back to a server fetch.");
        } else if (SignalStore.internalValues().gv2IgnoreP2PChanges()) {
          Log.w(TAG, "Ignoring P2P group change by setting");
        } else {
          try {
            Log.i(TAG, "Applying P2P group change");
            DecryptedGroup newState = DecryptedGroupUtil.apply(localState, signedGroupChange);

            inputGroupState = new GlobalGroupState(localState, Collections.singletonList(new ServerGroupLogEntry(newState, signedGroupChange)));
          } catch (NotAbleToApplyGroupV2ChangeException e) {
            Log.w(TAG, "Unable to apply P2P group change", e);
          }
        }
      }

      if (inputGroupState == null) {
        try {
          return updateLocalGroupFromServerPaged(revision, localState, timestamp, false);
        } catch (GroupNotAMemberException e) {
          if (localState != null && signedGroupChange != null) {
            try {
              if (notInGroupAndNotBeingAdded(localRecord, signedGroupChange)) {
                Log.w(TAG, "Server says we're not a member. Ignoring P2P group change because we're not currently in the group and this change doesn't add us in.");
              } else {
                Log.i(TAG, "Server says we're not a member. Applying P2P group change.");
                DecryptedGroup newState = DecryptedGroupUtil.applyWithoutRevisionCheck(localState, signedGroupChange);

                inputGroupState = new GlobalGroupState(localState, Collections.singletonList(new ServerGroupLogEntry(newState, signedGroupChange)));
              }
            } catch (NotAbleToApplyGroupV2ChangeException failed) {
              Log.w(TAG, "Unable to apply P2P group change when not a member", failed);
            }
          }

          if (inputGroupState == null) {
            if (localState != null && DecryptedGroupUtil.isPendingOrRequesting(localState, selfAci.uuid())) {
              Log.w(TAG, "Unable to query server for group " + groupId + " server says we're not in group, but we think we are a pending or requesting member");
            } else {
              Log.w(TAG, "Unable to query server for group " + groupId + " server says we're not in group, inserting leave message");
              insertGroupLeave();
            }
            throw e;
          }
        }
      }

      AdvanceGroupStateResult advanceGroupStateResult = GroupStateMapper.partiallyAdvanceGroupState(inputGroupState, revision);
      DecryptedGroup          newLocalState           = advanceGroupStateResult.getNewGlobalGroupState().getLocalState();

      if (newLocalState == null || newLocalState == inputGroupState.getLocalState()) {
        return new GroupUpdateResult(GroupState.GROUP_CONSISTENT_OR_AHEAD, null);
      }

      updateLocalDatabaseGroupState(inputGroupState, newLocalState);
      if (localState != null && localState.getRevision() == GroupsV2StateProcessor.RESTORE_PLACEHOLDER_REVISION) {
        Log.i(TAG, "Inserting single update message for restore placeholder");
        profileAndMessageHelper.insertUpdateMessages(timestamp, null, Collections.singleton(new LocalGroupLogEntry(newLocalState, null)));
      } else {
        profileAndMessageHelper.insertUpdateMessages(timestamp, localState, advanceGroupStateResult.getProcessedLogEntries());
      }
      profileAndMessageHelper.persistLearnedProfileKeys(inputGroupState);

      GlobalGroupState remainingWork = advanceGroupStateResult.getNewGlobalGroupState();
      if (remainingWork.getServerHistory().size() > 0) {
        Log.i(TAG, String.format(Locale.US, "There are more revisions on the server for this group, scheduling for later, V[%d..%d]", newLocalState.getRevision() + 1, remainingWork.getLatestRevisionNumber()));
        ApplicationDependencies.getJobManager().add(new RequestGroupV2InfoJob(groupId, remainingWork.getLatestRevisionNumber()));
      }

      return new GroupUpdateResult(GroupState.GROUP_UPDATED, newLocalState);
    }

    private boolean notInGroupAndNotBeingAdded(@NonNull Optional<GroupRecord> localRecord, @NonNull DecryptedGroupChange signedGroupChange) {
      boolean currentlyInGroup = localRecord.isPresent() && localRecord.get().isActive();

      boolean addedAsMember = signedGroupChange.getNewMembersList()
                                               .stream()
                                               .map(DecryptedMember::getUuid)
                                               .map(UuidUtil::fromByteStringOrNull)
                                               .filter(Objects::nonNull)
                                               .collect(Collectors.toSet())
                                               .contains(selfAci.uuid());

      boolean addedAsPendingMember = signedGroupChange.getNewPendingMembersList()
                                                      .stream()
                                                      .map(DecryptedPendingMember::getUuid)
                                                      .map(UuidUtil::fromByteStringOrNull)
                                                      .filter(Objects::nonNull)
                                                      .collect(Collectors.toSet())
                                                      .contains(selfAci.uuid());

      return !currentlyInGroup && !addedAsMember && !addedAsPendingMember;
    }

    /**
     * Using network, attempt to bring the local copy of the group up to the revision specified via paging.
     */
    private GroupUpdateResult updateLocalGroupFromServerPaged(int revision, DecryptedGroup localState, long timestamp, boolean forceIncludeFirst) throws IOException, GroupNotAMemberException {
      boolean latestRevisionOnly = revision == LATEST && (localState == null || localState.getRevision() == GroupsV2StateProcessor.RESTORE_PLACEHOLDER_REVISION);
      ACI     selfAci            = this.selfAci;

      Log.i(TAG, "Paging from server revision: " + (revision == LATEST ? "latest" : revision) + ", latestOnly: " + latestRevisionOnly);

      PartialDecryptedGroup latestServerGroup;
      GlobalGroupState      inputGroupState;

      try {
        latestServerGroup = groupsV2Api.getPartialDecryptedGroup(groupSecretParams, groupsV2Authorization.getAuthorizationForToday(selfAci, groupSecretParams));
      } catch (NotInGroupException | GroupNotFoundException e) {
        throw new GroupNotAMemberException(e);
      } catch (VerificationFailedException | InvalidGroupStateException e) {
        throw new IOException(e);
      }

      if (localState != null && localState.getRevision() >= latestServerGroup.getRevision()) {
        Log.i(TAG, "Local state is at or later than server");
        return new GroupUpdateResult(GroupState.GROUP_CONSISTENT_OR_AHEAD, null);
      }

      if (latestRevisionOnly || !GroupProtoUtil.isMember(selfAci.uuid(), latestServerGroup.getMembersList())) {
        Log.i(TAG, "Latest revision or not a member, use latest only");
        inputGroupState = new GlobalGroupState(localState, Collections.singletonList(new ServerGroupLogEntry(latestServerGroup.getFullyDecryptedGroup(), null)));
      } else {
        int     revisionWeWereAdded = GroupProtoUtil.findRevisionWeWereAdded(latestServerGroup, selfAci.uuid());
        int     logsNeededFrom      = localState != null ? Math.max(localState.getRevision(), revisionWeWereAdded) : revisionWeWereAdded;
        boolean includeFirstState   = forceIncludeFirst ||
                                      localState == null ||
                                      localState.getRevision() < 0 ||
                                      (revision == LATEST && localState.getRevision() + 1 < latestServerGroup.getRevision());

        Log.i(TAG,
              "Requesting from server currentRevision: " + (localState != null ? localState.getRevision() : "null") +
              " logsNeededFrom: " + logsNeededFrom +
              " includeFirstState: " + includeFirstState +
              " forceIncludeFirst: " + forceIncludeFirst);
        inputGroupState = getFullMemberHistoryPage(localState, selfAci, logsNeededFrom, includeFirstState);
      }

      ProfileKeySet    profileKeys           = new ProfileKeySet();
      DecryptedGroup   finalState            = localState;
      GlobalGroupState finalGlobalGroupState = inputGroupState;

      boolean hasMore = true;

      while (hasMore) {
        AdvanceGroupStateResult advanceGroupStateResult = GroupStateMapper.partiallyAdvanceGroupState(inputGroupState, revision);
        DecryptedGroup          newLocalState           = advanceGroupStateResult.getNewGlobalGroupState().getLocalState();
        Log.i(TAG, "Advanced group to revision: " + (newLocalState != null ? newLocalState.getRevision() : "null"));

        if (newLocalState != null && !inputGroupState.hasMore() && !forceIncludeFirst) {
          int newLocalRevision = newLocalState.getRevision();
          int requestRevision = (revision == LATEST) ? latestServerGroup.getRevision() : revision;
          if (newLocalRevision < requestRevision) {
            Log.w(TAG, "Paging again with force first snapshot enabled due to error processing changes. New local revision [" + newLocalRevision + "] hasn't reached our desired level [" + requestRevision + "]");
            return updateLocalGroupFromServerPaged(revision, localState, timestamp, true);
          }
        }

        if (newLocalState == null || newLocalState == inputGroupState.getLocalState()) {
          return new GroupUpdateResult(GroupState.GROUP_CONSISTENT_OR_AHEAD, null);
        }

        updateLocalDatabaseGroupState(inputGroupState, newLocalState);

        if (localState == null || localState.getRevision() != GroupsV2StateProcessor.RESTORE_PLACEHOLDER_REVISION) {
          timestamp = profileAndMessageHelper.insertUpdateMessages(timestamp, localState, advanceGroupStateResult.getProcessedLogEntries());
        }

        for (ServerGroupLogEntry entry : inputGroupState.getServerHistory()) {
          if (entry.getGroup() != null) {
            profileKeys.addKeysFromGroupState(entry.getGroup());
          }
          if (entry.getChange() != null) {
            profileKeys.addKeysFromGroupChange(entry.getChange());
          }
        }

        finalState            = newLocalState;
        finalGlobalGroupState = advanceGroupStateResult.getNewGlobalGroupState();
        hasMore               = inputGroupState.hasMore();

        if (hasMore) {
          Log.i(TAG, "Request next page from server revision: " + finalState.getRevision() + " nextPageRevision: " + inputGroupState.getNextPageRevision());
          inputGroupState = getFullMemberHistoryPage(finalState, selfAci, inputGroupState.getNextPageRevision(), false);
        }
      }

      if (localState != null && localState.getRevision() == GroupsV2StateProcessor.RESTORE_PLACEHOLDER_REVISION) {
        Log.i(TAG, "Inserting single update message for restore placeholder");
        profileAndMessageHelper.insertUpdateMessages(timestamp, null, Collections.singleton(new LocalGroupLogEntry(finalState, null)));
      }

      profileAndMessageHelper.persistLearnedProfileKeys(profileKeys);

      if (finalGlobalGroupState.getServerHistory().size() > 0) {
        Log.i(TAG, String.format(Locale.US, "There are more revisions on the server for this group, scheduling for later, V[%d..%d]", finalState.getRevision() + 1, finalGlobalGroupState.getLatestRevisionNumber()));
        ApplicationDependencies.getJobManager().add(new RequestGroupV2InfoJob(groupId, finalGlobalGroupState.getLatestRevisionNumber()));
      }

      return new GroupUpdateResult(GroupState.GROUP_UPDATED, finalState);
    }

    @WorkerThread
    public @NonNull DecryptedGroup getCurrentGroupStateFromServer()
        throws IOException, GroupNotAMemberException, GroupDoesNotExistException
    {
      try {
        return groupsV2Api.getGroup(groupSecretParams, groupsV2Authorization.getAuthorizationForToday(selfAci, groupSecretParams));
      } catch (GroupNotFoundException e) {
        throw new GroupDoesNotExistException(e);
      } catch (NotInGroupException e) {
        throw new GroupNotAMemberException(e);
      } catch (VerificationFailedException | InvalidGroupStateException e) {
        throw new IOException(e);
      }
    }

    @WorkerThread
    public @Nullable DecryptedGroup getSpecificVersionFromServer(int revision)
        throws IOException, GroupNotAMemberException, GroupDoesNotExistException
    {
      try {
        return groupsV2Api.getGroupHistoryPage(groupSecretParams, revision, groupsV2Authorization.getAuthorizationForToday(selfAci, groupSecretParams), true)
                          .getResults()
                          .get(0)
                          .getGroup()
                          .orNull();
      } catch (GroupNotFoundException e) {
        throw new GroupDoesNotExistException(e);
      } catch (NotInGroupException e) {
        throw new GroupNotAMemberException(e);
      } catch (VerificationFailedException | InvalidGroupStateException e) {
        throw new IOException(e);
      }
    }

    private void insertGroupLeave() {
      if (!groupDatabase.isActive(groupId)) {
        Log.w(TAG, "Group has already been left.");
        return;
      }

      Recipient groupRecipient = Recipient.externalGroupExact(context, groupId);
      UUID      selfUuid       = selfAci.uuid();

      DecryptedGroup decryptedGroup = groupDatabase.requireGroup(groupId)
                                                   .requireV2GroupProperties()
                                                   .getDecryptedGroup();

      DecryptedGroup simulatedGroupState = DecryptedGroupUtil.removeMember(decryptedGroup, selfUuid, decryptedGroup.getRevision() + 1);

      DecryptedGroupChange simulatedGroupChange = DecryptedGroupChange.newBuilder()
                                                                      .setEditor(UuidUtil.toByteString(UuidUtil.UNKNOWN_UUID))
                                                                      .setRevision(simulatedGroupState.getRevision())
                                                                      .addDeleteMembers(UuidUtil.toByteString(selfUuid))
                                                                      .build();

      DecryptedGroupV2Context decryptedGroupV2Context = GroupProtoUtil.createDecryptedGroupV2Context(masterKey, new GroupMutation(decryptedGroup, simulatedGroupChange, simulatedGroupState), null);

      OutgoingGroupUpdateMessage leaveMessage = new OutgoingGroupUpdateMessage(groupRecipient,
                                                                               decryptedGroupV2Context,
                                                                               null,
                                                                               System.currentTimeMillis(),
                                                                               0,
                                                                               false,
                                                                               null,
                                                                               Collections.emptyList(),
                                                                               Collections.emptyList(),
                                                                               Collections.emptyList());

      try {
        MessageDatabase mmsDatabase    = SignalDatabase.mms();
        ThreadDatabase  threadDatabase = SignalDatabase.threads();
        long            threadId       = threadDatabase.getOrCreateThreadIdFor(groupRecipient);
        long            id             = mmsDatabase.insertMessageOutbox(leaveMessage, threadId, false, null);
        mmsDatabase.markAsSent(id, true);
        threadDatabase.update(threadId, false, false);
      } catch (MmsException e) {
        Log.w(TAG, "Failed to insert leave message.", e);
      }

      groupDatabase.setActive(groupId, false);
      groupDatabase.remove(groupId, Recipient.self().getId());
    }

    /**
     * @return true iff group exists locally and is at least the specified revision.
     */
    private boolean localIsAtLeast(int revision) {
      if (groupDatabase.isUnknownGroup(groupId) || revision == LATEST) {
        return false;
      }
      int dbRevision = groupDatabase.getGroup(groupId).get().requireV2GroupProperties().getGroupRevision();
      return revision <= dbRevision;
    }

    private void updateLocalDatabaseGroupState(@NonNull GlobalGroupState inputGroupState,
                                               @NonNull DecryptedGroup newLocalState)
    {
      boolean needsAvatarFetch;

      if (inputGroupState.getLocalState() == null) {
        groupDatabase.create(masterKey, newLocalState);
        needsAvatarFetch = !TextUtils.isEmpty(newLocalState.getAvatar());
      } else {
        groupDatabase.update(masterKey, newLocalState);
        needsAvatarFetch = !newLocalState.getAvatar().equals(inputGroupState.getLocalState().getAvatar());
      }

      if (needsAvatarFetch) {
        ApplicationDependencies.getJobManager().add(new AvatarGroupsV2DownloadJob(groupId, newLocalState.getAvatar()));
      }

      profileAndMessageHelper.determineProfileSharing(inputGroupState, newLocalState);
    }

    private GlobalGroupState getFullMemberHistoryPage(DecryptedGroup localState, @NonNull ACI selfAci, int logsNeededFromRevision, boolean includeFirstState) throws IOException {
      try {
        GroupHistoryPage               groupHistoryPage    = groupsV2Api.getGroupHistoryPage(groupSecretParams, logsNeededFromRevision, groupsV2Authorization.getAuthorizationForToday(selfAci, groupSecretParams), includeFirstState);
        ArrayList<ServerGroupLogEntry> history             = new ArrayList<>(groupHistoryPage.getResults().size());
        boolean                        ignoreServerChanges = SignalStore.internalValues().gv2IgnoreServerChanges();

        if (ignoreServerChanges) {
          Log.w(TAG, "Server change logs are ignored by setting");
        }

        for (DecryptedGroupHistoryEntry entry : groupHistoryPage.getResults()) {
          DecryptedGroup       group  = entry.getGroup().orNull();
          DecryptedGroupChange change = ignoreServerChanges ? null : entry.getChange().orNull();

          if (group != null || change != null) {
            history.add(new ServerGroupLogEntry(group, change));
          }
        }

        return new GlobalGroupState(localState, history, groupHistoryPage.getPagingData());
      } catch (InvalidGroupStateException | VerificationFailedException e) {
        throw new IOException(e);
      }
    }
  }

  @VisibleForTesting
  static class ProfileAndMessageHelper {

    private final Context           context;
    private final ACI               selfAci;
    private final GroupMasterKey    masterKey;
    private final GroupId.V2        groupId;
    private final RecipientDatabase recipientDatabase;

    ProfileAndMessageHelper(@NonNull Context context, @NonNull ACI selfAci, @NonNull GroupMasterKey masterKey, @NonNull GroupId.V2 groupId, @NonNull RecipientDatabase recipientDatabase) {
      this.context           = context;
      this.selfAci           = selfAci;
      this.masterKey         = masterKey;
      this.groupId           = groupId;
      this.recipientDatabase = recipientDatabase;
    }

    void determineProfileSharing(@NonNull GlobalGroupState inputGroupState, @NonNull DecryptedGroup newLocalState) {
      if (inputGroupState.getLocalState() != null) {
        boolean wasAMemberAlready = DecryptedGroupUtil.findMemberByUuid(inputGroupState.getLocalState().getMembersList(), selfAci.uuid()).isPresent();

        if (wasAMemberAlready) {
          return;
        }
      }

      Optional<DecryptedMember> selfAsMemberOptional = DecryptedGroupUtil.findMemberByUuid(newLocalState.getMembersList(), selfAci.uuid());

      if (selfAsMemberOptional.isPresent()) {
        DecryptedMember selfAsMember     = selfAsMemberOptional.get();
        int             revisionJoinedAt = selfAsMember.getJoinedAtRevision();

        Optional<Recipient> addedByOptional = Stream.of(inputGroupState.getServerHistory())
                                                    .map(ServerGroupLogEntry::getChange)
                                                    .filter(c -> c != null && c.getRevision() == revisionJoinedAt)
                                                    .findFirst()
                                                    .map(c -> Optional.fromNullable(UuidUtil.fromByteStringOrNull(c.getEditor()))
                                                                      .transform(a -> Recipient.externalPush(ACI.fromByteStringOrNull(c.getEditor()), null, false)))
                                                    .orElse(Optional.absent());

        if (addedByOptional.isPresent()) {
          Recipient addedBy = addedByOptional.get();

          Log.i(TAG, String.format("Added as a full member of %s by %s", groupId, addedBy.getId()));

          if (addedBy.isSystemContact() || addedBy.isProfileSharing()) {
            Log.i(TAG, "Group 'adder' is trusted. contact: " + addedBy.isSystemContact() + ", profileSharing: " + addedBy.isProfileSharing());
            Log.i(TAG, "Added to a group and auto-enabling profile sharing");
            recipientDatabase.setProfileSharing(Recipient.externalGroupExact(context, groupId).getId(), true);
          } else {
            Log.i(TAG, "Added to a group, but not enabling profile sharing, as 'adder' is not trusted");
          }
        } else {
          Log.w(TAG, "Could not find founding member during gv2 create. Not enabling profile sharing.");
        }
      } else {
        Log.i(TAG, String.format("Added to %s, but not enabling profile sharing as not a fullMember.", groupId));
      }
    }

    long insertUpdateMessages(long timestamp,
                              @Nullable DecryptedGroup previousGroupState,
                              Collection<LocalGroupLogEntry> processedLogEntries)
    {
      for (LocalGroupLogEntry entry : processedLogEntries) {
        if (entry.getChange() != null && DecryptedGroupUtil.changeIsEmptyExceptForProfileKeyChanges(entry.getChange()) && !DecryptedGroupUtil.changeIsEmpty(entry.getChange())) {
          Log.d(TAG, "Skipping profile key changes only update message");
        } else {
          if (entry.getChange() != null && DecryptedGroupUtil.changeIsEmpty(entry.getChange()) && previousGroupState != null) {
            Log.w(TAG, "Empty group update message seen. Not inserting.");
          } else {
            storeMessage(GroupProtoUtil.createDecryptedGroupV2Context(masterKey, new GroupMutation(previousGroupState, entry.getChange(), entry.getGroup()), null), timestamp);
            timestamp++;
          }
        }
        previousGroupState = entry.getGroup();
      }
      return timestamp;
    }

    void persistLearnedProfileKeys(@NonNull GlobalGroupState globalGroupState) {
      final ProfileKeySet profileKeys = new ProfileKeySet();

      for (ServerGroupLogEntry entry : globalGroupState.getServerHistory()) {
        if (entry.getGroup() != null) {
          profileKeys.addKeysFromGroupState(entry.getGroup());
        }
        if (entry.getChange() != null) {
          profileKeys.addKeysFromGroupChange(entry.getChange());
        }
      }

      persistLearnedProfileKeys(profileKeys);
    }

    void persistLearnedProfileKeys(@NonNull ProfileKeySet profileKeys) {
      Set<RecipientId> updated = recipientDatabase.persistProfileKeySet(profileKeys);

      if (!updated.isEmpty()) {
        Log.i(TAG, String.format(Locale.US, "Learned %d new profile keys, fetching profiles", updated.size()));

        for (Job job : RetrieveProfileJob.forRecipients(updated)) {
          ApplicationDependencies.getJobManager().runSynchronously(job, 5000);
        }
      }
    }

    void storeMessage(@NonNull DecryptedGroupV2Context decryptedGroupV2Context, long timestamp) {
      Optional<ACI> editor = getEditor(decryptedGroupV2Context).transform(ACI::from);

      boolean outgoing = !editor.isPresent() || selfAci.equals(editor.get());

      if (outgoing) {
        try {
          MessageDatabase            mmsDatabase     = SignalDatabase.mms();
          ThreadDatabase             threadDatabase  = SignalDatabase.threads();
          RecipientId                recipientId     = recipientDatabase.getOrInsertFromGroupId(groupId);
          Recipient                  recipient       = Recipient.resolved(recipientId);
          OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(recipient, decryptedGroupV2Context, null, timestamp, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
          long                       threadId        = threadDatabase.getOrCreateThreadIdFor(recipient);
          long                       messageId       = mmsDatabase.insertMessageOutbox(outgoingMessage, threadId, false, null);

          mmsDatabase.markAsSent(messageId, true);
          threadDatabase.update(threadId, false, false);
        } catch (MmsException e) {
          Log.w(TAG, e);
        }
      } else {
        MessageDatabase                        smsDatabase  = SignalDatabase.sms();
        RecipientId                            sender       = RecipientId.from(editor.get(), null);
        IncomingTextMessage                    incoming     = new IncomingTextMessage(sender, -1, timestamp, timestamp, timestamp, "", Optional.of(groupId), 0, false, null);
        IncomingGroupUpdateMessage             groupMessage = new IncomingGroupUpdateMessage(incoming, decryptedGroupV2Context);
        Optional<MessageDatabase.InsertResult> insertResult = smsDatabase.insertMessageInbox(groupMessage);

        if (insertResult.isPresent()) {
          SignalDatabase.threads().update(insertResult.get().getThreadId(), false, false);
        } else {
          Log.w(TAG, "Could not insert update message");
        }
      }
    }

    private Optional<UUID> getEditor(@NonNull DecryptedGroupV2Context decryptedGroupV2Context) {
      DecryptedGroupChange change       = decryptedGroupV2Context.getChange();
      Optional<UUID>       changeEditor = DecryptedGroupUtil.editorUuid(change);
      if (changeEditor.isPresent()) {
        return changeEditor;
      } else {
        Optional<DecryptedPendingMember> pendingByUuid = DecryptedGroupUtil.findPendingByUuid(decryptedGroupV2Context.getGroupState().getPendingMembersList(), selfAci.uuid());
        if (pendingByUuid.isPresent()) {
          return Optional.fromNullable(UuidUtil.fromByteStringOrNull(pendingByUuid.get().getAddedByUuid()));
        }
      }
      return Optional.absent();
    }
  }
}

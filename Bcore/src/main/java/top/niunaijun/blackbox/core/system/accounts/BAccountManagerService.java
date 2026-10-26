package top.niunaijun.blackbox.core.system.accounts;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.accounts.IAccountManagerResponse;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.core.util.AtomicFile;
import androidx.core.util.Preconditions;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.am.BActivityManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.pm.PackageMonitor;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.utils.ArrayUtils;
import top.niunaijun.blackbox.utils.CloseUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.AccountManagerCompat;


@SuppressLint("InlinedApi")
public class BAccountManagerService extends IBAccountManagerService.Stub implements ISystemService , PackageMonitor {
    private static final String TAG = "AccountManagerService";

    private static BAccountManagerService sService = new BAccountManagerService();

    private static final Account[] EMPTY_ACCOUNT_ARRAY = new Account[]{};

    
    private static final int MESSAGE_TIMED_OUT = 3;
    private static final int MESSAGE_COPY_SHARED_ACCOUNT = 4;

    private final BPackageManagerService mPms;
    private final Map<Integer, BUserAccounts> mUserAccountsMap = new HashMap<>();
    private final AuthenticatorCache mAuthenticatorCache = new AuthenticatorCache();

    private final LinkedList<TokenCache> mTokenCaches = new LinkedList<>();
    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<String, Session>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Context mContext;

    public static BAccountManagerService get() {
        return sService;
    }

    public BAccountManagerService() {
        mContext = BlackBoxCore.getContext();
        mPms = BPackageManagerService.get();
    }

    @Override
    public void systemReady() {
        loadAccounts();
        loadAuthenticatorCache(null);
        mPms.addPackageMonitor(this);
    }

    @Override
    public void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
        loadAuthenticatorCache(null);
    }

    @Override
    public void onPackageInstalled(String packageName, int userId) {
        // The authenticator registry is global to the virtual package manager.
        // Never replace it with only this newly installed package.
        loadAuthenticatorCache(null);
    }

    private void loadAccounts() {
        Parcel parcel = Parcel.obtain();
        InputStream is = null;
        try {
            File userInfoConf = BEnvironment.getAccountsConf();
            if (!userInfoConf.exists()) {
                return;
            }
            is = new FileInputStream(BEnvironment.getAccountsConf());
            byte[] bytes = FileUtils.toByteArray(is);
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);

            HashMap<Integer, BUserAccounts> accountsMap = parcel.readHashMap(BUserAccounts.class.getClassLoader());
            if (accountsMap == null)
                return;
            synchronized (mUserAccountsMap) {
                mUserAccountsMap.clear();
                for (Integer key : accountsMap.keySet()) {
                    if (key == null || accountsMap.get(key) == null) continue;
                    BUserAccounts restored = accountsMap.get(key);
                    // Old versions did not initialize userId and persisted 0
                    // for every newly-created virtual user account.
                    restored.userId = key;
                    mUserAccountsMap.put(key, restored);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            parcel.recycle();
            CloseUtils.close(is);
        }
    }

    private void saveAllAccounts() {
        synchronized (mUserAccountsMap) {
            Parcel parcel = Parcel.obtain();
            AtomicFile atomicFile = new AtomicFile(BEnvironment.getAccountsConf());
            FileOutputStream fileOutputStream = null;
            try {
                parcel.writeMap(mUserAccountsMap);
                try {
                    fileOutputStream = atomicFile.startWrite();
                    FileUtils.writeParcelToOutput(parcel, fileOutputStream);
                    atomicFile.finishWrite(fileOutputStream);
                } catch (IOException e) {
                    e.printStackTrace();
                    atomicFile.failWrite(fileOutputStream);
                } finally {
                    CloseUtils.close(fileOutputStream);
                }
            } finally {
                parcel.recycle();
            }
        }
    }

    @Override
    public String getPassword(Account account, int userId) throws RemoteException {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "getPassword: " + account
                    + ", caller's uid " + Binder.getCallingUid()
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) throw new IllegalArgumentException("account is null");
        BUserAccounts accounts = getUserAccounts(userId);
        return readPasswordInternal(accounts, account);
    }

    @Override
    public String getUserData(Account account, String key, int userId) throws RemoteException {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            String msg = String.format("getUserData( account: %s, key: %s, callerUid: %s, pid: %s",
                    account, key, Binder.getCallingUid(), Binder.getCallingPid());
            Log.v(TAG, msg);
        }
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        return readUserDataInternal(accounts, account, key);
    }

    @Override
    public AuthenticatorDescription[] getAuthenticatorTypes(int userId) throws RemoteException {
        // AccountManager must expose installed AUTHENTICATORS even when the
        // user has zero accounts. Otherwise the first Google login is impossible.
        List<AuthenticatorDescription> authenticatorDescriptions = new ArrayList<>();
        for (AuthenticatorInfo provider : mAuthenticatorCache.authenticators.values()) {
            if (mPms.isInstalled(provider.serviceInfo.packageName, userId)) {
                authenticatorDescriptions.add(provider.desc);
            }
        }
        return authenticatorDescriptions.toArray(new AuthenticatorDescription[0]);
    }

    @Override
    public Account[] getAccountsForPackage(String packageName, int uid, int userId) throws RemoteException {
        
        BUserAccounts userAccounts = getUserAccounts(userId);
        List<Account> accounts = new ArrayList<>();
        synchronized (userAccounts.lock) {
            for (BAccount entry : userAccounts.accounts) {
                int visibility = resolveAccountVisibility(entry.account, packageName, userAccounts);
                if (visibility == AccountManager.VISIBILITY_VISIBLE
                        || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE) {
                    accounts.add(entry.account);
                }
            }
        }
        return accounts.toArray(new Account[0]);
    }

    @Override
    public Account[] getAccountsByTypeForPackage(String type, String packageName, int userId) throws RemoteException {
        
        BUserAccounts userAccounts = getUserAccounts(userId);
        List<Account> accounts = new ArrayList<>();
        synchronized (userAccounts.lock) {
            for (BAccount account : userAccounts.accounts) {
                if (type == null || account.account.type.equals(type)) {
                    int visibility = resolveAccountVisibility(account.account, packageName, userAccounts);
                    if (visibility == AccountManager.VISIBILITY_VISIBLE
                            || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE) {
                        accounts.add(account.account);
                    }
                }
            }
        }
        return accounts.toArray(new Account[]{});
    }

    @Override
    public Account[] getAccountsAsUser(String accountType, int userId) throws RemoteException {
        BUserAccounts userAccounts = getUserAccounts(userId);
        List<Account> accounts = new ArrayList<>();
        synchronized (userAccounts.lock) {
            for (BAccount account : userAccounts.accounts) {
                if (accountType == null || account.account.type.equals(accountType)) {
                    accounts.add(account.account);
                }
            }
        }
        return accounts.toArray(new Account[]{});
    }

    @Override
    public void getAccountByTypeAndFeatures(IAccountManagerResponse response, String accountType, String[] features, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        String opPackageName = getCallingPackageName();

        BUserAccounts userAccounts = getUserAccounts(userId);
        if (ArrayUtils.isEmpty(features)) {
            Account[] accountsWithManagedNotVisible = getAccountsFromCache(
                    userAccounts, accountType, opPackageName,
                    true );
            handleGetAccountsResult(
                    response, accountsWithManagedNotVisible, opPackageName, userId);
            return;
        }

        IAccountManagerResponse retrieveAccountsResponse =
                new IAccountManagerResponse.Stub() {
                    @Override
                    public void onResult(Bundle value) throws RemoteException {
                        Parcelable[] parcelables = value.getParcelableArray(
                                AccountManager.KEY_ACCOUNTS);
                        Account[] accounts = new Account[parcelables.length];
                        for (int i = 0; i < parcelables.length; i++) {
                            accounts[i] = (Account) parcelables[i];
                        }
                        handleGetAccountsResult(
                                response, accounts, opPackageName, userId);
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage)
                            throws RemoteException {
                        
                    }
                };
        new GetAccountsByTypeAndFeatureSession(
                userAccounts,
                retrieveAccountsResponse,
                accountType,
                features,
                userId,
                opPackageName,
                true ).bind();
    }

    @Override
    public void getAccountsByFeatures(IAccountManagerResponse response, String type, String[] features, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (type == null) throw new IllegalArgumentException("accountType is null");

        String opPackageName = getCallingPackageName();
        
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (features == null || features.length == 0) {
            Account[] accounts = getAccountsFromCache(userAccounts, type,
                    opPackageName, false);
            Bundle result = new Bundle();
            result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
            onResult(response, result);
            return;
        }
        new GetAccountsByTypeAndFeatureSession(
                userAccounts,
                response,
                type,
                features,
                userId,
                opPackageName,
                false ).bind();
    }

    @Override
    public boolean addAccountExplicitly(Account account, String password, Bundle extras, int userId) throws RemoteException {
        return addAccountExplicitlyWithVisibility(account, password, extras, null, userId);
    }

    @Override
    public void removeAccountAsUser(IAccountManagerResponse response, Account account, boolean expectActivityLaunch, int userId) throws RemoteException {
        Preconditions.checkArgument(account != null, "account cannot be null");
        Preconditions.checkArgument(response != null, "response cannot be null");
        
        
        BUserAccounts accounts = getUserAccounts(userId);
        new RemoveAccountSession(accounts, response, account, expectActivityLaunch).bind();
    }

    @Override
    public boolean removeAccountExplicitly(Account account, int userId) throws RemoteException {
        final int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "removeAccountExplicitly: " + account
                    + ", caller's uid " + callingUid
                    + ", pid " + Binder.getCallingPid());
        }
        if (account == null) {
            
            Log.e(TAG, "account is null");
            return false;
        }
        BUserAccounts accounts = getUserAccounts(userId);
        return removeAccountInternal(accounts, account);
    }

    @Override
    public void copyAccountToUser(IAccountManagerResponse response, Account account, int userFrom, int userTo) throws RemoteException {
        final BUserAccounts fromAccounts = getUserAccounts(userFrom);
        final BUserAccounts toAccounts = getUserAccounts(userTo);
        if (fromAccounts == null || toAccounts == null) {
            if (response != null) {
                Bundle result = new Bundle();
                result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false);
                try {
                    response.onResult(result);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to report error back to the client." + e);
                }
            }
            return;
        }

        Slog.d(TAG, "Copying account " + account.toString()
                + " from user " + userFrom + " to user " + userTo);
        new Session(fromAccounts, response, account.type, false,
                false , account.name,
                false ) {
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", getAccountCredentialsForClone"
                        + ", " + account.type;
            }

            @Override
            public void run() throws RemoteException {
                mAuthenticator.getAccountCredentialsForCloning(this, account);
            }

            @Override
            public void onResult(Bundle result) {
                if (result != null
                        && result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                    
                    completeCloningAccount(response, result, account, toAccounts, userFrom);
                } else {
                    super.onResult(result);
                }
            }
        }.bind();
    }

    @Override
    public void invalidateAuthToken(String accountType, String authToken, int userId) throws RemoteException {
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            boolean changed = false;
            for (BAccount account : accounts.accounts) {
                if (account.account.type.equals(accountType)) {
                    account.accountUserData.values().remove(authToken);
                    changed = true;
                }
            }
            if (changed) {
                saveAllAccounts();
            }
        }

        synchronized (mTokenCaches) {
            Iterator<TokenCache> iterator = mTokenCaches.iterator();
            while (iterator.hasNext()) {
                TokenCache next = iterator.next();
                if (next.account.type.equals(accountType) && next.userId == userId && next.authToken.equals(authToken)) {
                    iterator.remove();
                }
            }
        }
    }

    @Override
    public String peekAuthToken(Account account, String authTokenType, int userId) throws RemoteException {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(authTokenType, "authTokenType cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            return accounts.getAuthToken(account).get(authTokenType);
        }
    }

    @Override
    public void setAuthToken(Account account, String authTokenType, String authToken, int userId) throws RemoteException {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(authTokenType, "authTokenType cannot be null");

        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            accounts.getAuthToken(account).put(authTokenType, authToken);
            saveAllAccounts();
        }
    }

    @Override
    public void setPassword(Account account, String password, int userId) throws RemoteException {
        Objects.requireNonNull(account, "account cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            bAccount.password = password;
            bAccount.authTokens.clear();
            saveAllAccounts();
        }
        synchronized (mTokenCaches) {
            Iterator<TokenCache> iterator = mTokenCaches.iterator();
            while (iterator.hasNext()) {
                TokenCache next = iterator.next();
                if (next.account.equals(account) && next.userId == userId) {
                    iterator.remove();
                }
            }
        }
    }

    @Override
    public void clearPassword(Account account, int userId) throws RemoteException {
        setPassword(account, null, userId);
    }

    @Override
    public void setUserData(Account account, String key, String value, int userId) throws RemoteException {
        if (key == null) throw new IllegalArgumentException("key is null");
        if (account == null) throw new IllegalArgumentException("account is null");

        BUserAccounts accounts = getUserAccounts(userId);
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            accounts.getAccountUserData(account).put(key, value);
            saveAllAccounts();
        }
    }

    @Override
    public void updateAppPermission(Account account, String authTokenType, int uid, boolean value) throws RemoteException {
        
    }

    @Override
    public void getAuthToken(IAccountManagerResponse response, Account account, String authTokenType, boolean notifyOnAuthFailure, boolean expectActivityLaunch, Bundle loginOptions, int userId) throws RemoteException {
        Preconditions.checkArgument(response != null, "response cannot be null");
        try {
            if (account == null) {
                Slog.w(TAG, "getAuthToken called with null account");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "account is null");
                return;
            }
            if (authTokenType == null) {
                Slog.w(TAG, "getAuthToken called with null authTokenType");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "authTokenType is null");
                return;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to report error back to the client." + e);
            return;
        }
        final BUserAccounts accounts = getUserAccounts(userId);
        AuthenticatorInfo authenticatorInfo = mAuthenticatorCache.authenticators.get(account.type);

        final boolean customTokens =
                authenticatorInfo != null && authenticatorInfo.desc.customTokens;

        
        // Android allows null options. Passing it through previously caused
        // a NullPointerException before the authenticator could be contacted.
        final Bundle authOptions = loginOptions == null ? new Bundle() : new Bundle(loginOptions);
        final String callerPkg = authOptions.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);

        authOptions.putInt(AccountManager.KEY_CALLER_UID, Binder.getCallingUid());
        authOptions.putInt(AccountManager.KEY_CALLER_PID, Binder.getCallingPid());

        if (notifyOnAuthFailure) {
            authOptions.putBoolean(AccountManagerCompat.KEY_NOTIFY_ON_FAILURE, true);
        }

        
        
        if (!customTokens) {
            String authToken = readAuthTokenInternal(accounts, account, authTokenType);
            if (authToken != null) {
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_AUTHTOKEN, authToken);
                result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
                onResult(response, result);
                return;
            }
        }

        if (customTokens) {
            
            String token = readCachedTokenInternal(
                    accounts,
                    account,
                    authTokenType,
                    callerPkg);
            if (token != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "getAuthToken: cache hit ofr custom token authenticator.");
                }
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_AUTHTOKEN, token);
                result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
                onResult(response, result);
                return;
            }
        }

        new Session(
                accounts,
                response,
                account.type,
                expectActivityLaunch,
                false ,
                account.name,
                false ) {
            @Override
            protected String toDebugString(long now) {
                // Never log login bundles or account names: they may contain
                // private authentication data supplied by Google.
                return super.toDebugString(now) + ", getAuthToken"
                        + ", accountType " + account.type
                        + ", notifyOnAuthFailure " + notifyOnAuthFailure;
            }

            @Override
            public void run() throws RemoteException {
                
                
                mAuthenticator.getAuthToken(this, account, authTokenType, authOptions);
            }

            @Override
            public void onResult(Bundle result) {
                if (result != null) {
                    String authToken = result.getString(AccountManager.KEY_AUTHTOKEN);
                    if (authToken != null) {
                        String name = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                        String type = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                        if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name)) {
                            onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                    "the type and name should not be empty");
                            return;
                        }
                        Account resultAccount = new Account(name, type);
                        if (!customTokens) {
                            saveAuthTokenToDatabase(
                                    mAccounts,
                                    resultAccount,
                                    authTokenType,
                                    authToken);
                        }
                        long expiryMillis = result.getLong(
                                AbstractAccountAuthenticator.KEY_CUSTOM_TOKEN_EXPIRY, 0L);
                        if (customTokens
                                && expiryMillis > System.currentTimeMillis()) {
                            saveCachedToken(
                                    mAccounts,
                                    account,
                                    callerPkg,
                                    authTokenType,
                                    authToken,
                                    expiryMillis);
                        }
                    }

                    Intent intent = result.getParcelable(AccountManager.KEY_INTENT);
                    if (intent != null && notifyOnAuthFailure && !customTokens) {





                    }
                }
                super.onResult(result);
            }
        }.bind();
    }

    @Override
    public void addAccount(IAccountManagerResponse response, String accountType, String authTokenType, String[] requiredFeatures, boolean expectActivityLaunch, Bundle optionsIn, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");



        final Bundle options = (optionsIn == null) ? new Bundle() : optionsIn;



        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, accountType, expectActivityLaunch,
                true , null ,
                false , true ) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures,
                        options);
            }

            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", addAccount"
                        + ", accountType " + accountType
                        + ", requiredFeatures " + Arrays.toString(requiredFeatures);
            }
        }.bind();
    }

    @Override
    public void addAccountAsUser(IAccountManagerResponse response, String accountType, String authTokenType, String[] requiredFeatures, boolean expectActivityLaunch, Bundle options, int userId) throws RemoteException {
        // This was a no-op: clients requesting Google addAccountAsUser never
        // received a result. The virtual user is already supplied by our
        // binder protocol, so use the normal account-addition flow.
        addAccount(response, accountType, authTokenType, requiredFeatures,
                expectActivityLaunch, options, userId);
    }

    @Override
    public void updateCredentials(IAccountManagerResponse response, Account account, String authTokenType, boolean expectActivityLaunch, Bundle loginOptions, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        long identityToken = clearCallingIdentity();
        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, account.type, expectActivityLaunch,
                true , account.name,
                false , true ) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.updateCredentials(this, account, authTokenType, loginOptions);
            }
            @Override
            protected String toDebugString(long now) {
                if (loginOptions != null) loginOptions.keySet();
                return super.toDebugString(now) + ", updateCredentials"
                        + ", " + account.toString()
                        + ", authTokenType " + authTokenType
                        + ", loginOptions " + loginOptions;
            }
        }.bind();
    }

    @Override
    public void editProperties(IAccountManagerResponse response, String accountType, boolean expectActivityLaunch, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, accountType, expectActivityLaunch,
                true , null ,
                false ) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.editProperties(this, mAccountType);
            }
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", editProperties"
                        + ", accountType " + accountType;
            }
        }.bind();
    }

    @Override
    public void confirmCredentialsAsUser(IAccountManagerResponse response, Account account, Bundle options, boolean expectActivityLaunch, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");

        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, account.type, expectActivityLaunch,
                true /* stripAuthTokenFromResult */, account.name,
                true /* authDetailsRequired */, true /* updateLastAuthenticatedTime */) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.confirmCredentials(this, account, options);
            }
        }.bind();
    }

    @Override
    public boolean accountAuthenticated(Account account, int userId) throws RemoteException {
        Objects.requireNonNull(account, "account cannot be null");
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (userAccounts == null)
            return false;
        return updateLastAuthenticatedTime(userAccounts, account);
    }

    @Override
    public void getAuthTokenLabel(IAccountManagerResponse response, String accountType, String authTokenType, int userId) throws RemoteException {
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
        Preconditions.checkArgument(authTokenType != null, "authTokenType cannot be null");



        BUserAccounts accounts = getUserAccounts(userId);
        new Session(accounts, response, accountType, false ,
                false ,  null ,
                false ) {
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", getAuthTokenLabel"
                        + ", " + accountType
                        + ", authTokenType " + authTokenType;
            }

            @Override
            public void run() throws RemoteException {
                mAuthenticator.getAuthTokenLabel(this, authTokenType);
            }

            @Override
            public void onResult(Bundle result) {
                if (result != null) {
                    String label = result.getString(AccountManager.KEY_AUTH_TOKEN_LABEL);
                    Bundle bundle = new Bundle();
                    bundle.putString(AccountManager.KEY_AUTH_TOKEN_LABEL, label);
                    super.onResult(bundle);
                } else {
                    super.onResult(result);
                }
            }
        }.bind();
    }

    @Override
    public Map getPackagesAndVisibilityForAccount(Account account, int userId) throws RemoteException {
        return new HashMap<>();
    }

    protected void saveCachedToken(BUserAccounts accounts,
                                   Account account,
                                   String callerPkg,
                                   String tokenType,
                                   String token,
                                   long expiryMillis) {
        if (account == null || tokenType == null || callerPkg == null) {
            return;
        }
        TokenCache cache = new TokenCache(accounts.userId, account, callerPkg, tokenType, token, expiryMillis);
        synchronized (mTokenCaches) {
            mTokenCaches.add(cache);
        }
    }

    protected void saveAuthTokenToDatabase(BUserAccounts accounts, Account account, String
                                           authTokenType, String authToken) {
        if (accounts == null)
            return;
        synchronized (accounts.lock) {
            accounts.getAuthToken(account).put(authTokenType, authToken);
            saveAllAccounts();
        }
    }

    protected String readCachedTokenInternal(
            BUserAccounts accounts,
            Account account,
            String tokenType,
            String callingPackage) {
        long nowTime = System.currentTimeMillis();
        synchronized (mTokenCaches) {
            Iterator<TokenCache> iterator = mTokenCaches.iterator();
            while (iterator.hasNext()) {
                TokenCache next = iterator.next();

                if (next.userId == accounts.userId && next.account.equals(account) && next.authTokenType.equals(tokenType) && next.packageName.equals(callingPackage)) {
                    if (next.expiryEpochMillis > nowTime) {
                        return next.authToken;
                    } else {
                        iterator.remove();
                    }
                }
            }
            return null;
        }
    }

    protected String readAuthTokenInternal(BUserAccounts accounts, Account account,
                                           String authTokenType) {
        if (accounts == null)
            return null;
        
        synchronized (accounts.lock) {
            Map<String, String> authToken = accounts.getAuthToken(account);
            return authToken.get(authTokenType);
        }
    }

    private void completeCloningAccount(IAccountManagerResponse response,
                                        final Bundle accountCredentials, final Account account, final BUserAccounts targetUser,
                                        final int parentUserId){
        new Session(targetUser, response, account.type, false,
                false , account.name,
                false ) {
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", getAccountCredentialsForClone"
                        + ", " + account.type;
            }

            @Override
            public void run() throws RemoteException {
                
                for (Account acc : getAccounts(parentUserId, mContext.getPackageName())) {
                    if (acc.equals(account)) {
                        mAuthenticator.addAccountFromCredentials(
                                this, account, accountCredentials);
                        break;
                    }
                }
            }

            @Override
            public void onResult(Bundle result) {
                
                
                
                

                super.onResult(result);
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                super.onError(errorCode,  errorMessage);
                
                
            }

        }.bind();

    }

    public Account[] getAccounts(int userId, String opPackageName) {
        BUserAccounts userAccounts = getUserAccounts(userId);
        return userAccounts.accounts.toArray(new Account[]{});
    }

    @Override
    public boolean addAccountExplicitlyWithVisibility(Account account, String password,
                                                      Bundle extras, Map packageToVisibility, int userId) {
        
        
        BUserAccounts accounts = getUserAccounts(userId);
        return addAccountInternal(accounts, account, password, extras,
                (Map<String, Integer>) packageToVisibility);
    }

    @Override
    public boolean setAccountVisibility(Account account, String packageName, int newVisibility, int userId) throws RemoteException {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (userAccounts == null)
            return false;
        return setAccountVisibility(account, packageName, newVisibility, userAccounts);
    }

    @Override
    public int getAccountVisibility(Account account, String packageName, int userId) throws RemoteException {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)) {
            int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
            if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
                return visibility;
            } else {
                return AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
            }
        }
        if (AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName)) {
            int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
            if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
                return visibility;
            } else {
                return AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE;
            }
        }
        return resolveAccountVisibility(account, packageName, accounts);
    }

    @Override
    public Map getAccountsAndVisibilityForPackage(String packageName, String accountType, int userId) throws RemoteException {
        Map<Account, Integer> hashMap = new HashMap<>();
        BUserAccounts userAccounts = getUserAccounts(userId);
        synchronized (userAccounts.lock) {
            for (BAccount account : userAccounts.accounts) {
                if (account.account.type.equals(accountType)) {
                    Integer integer = userAccounts.getVisibility(account.account).get(packageName);
                    if (integer != null)
                        hashMap.put(account.account, integer);
                }
            }
        }
        return hashMap;
    }

    @Override
    public void registerAccountListener(String[] accountTypes, String opPackageName, int userId) throws RemoteException {

    }

    @Override
    public void unregisterAccountListener(String[] accountTypes, String opPackageName, int userId) throws RemoteException {

    }

    private boolean addAccountInternal(BUserAccounts accounts, Account account, String password,
                                       Bundle extras, Map<String, Integer> packageToVisibility) {
        if (accounts == null) {
            accounts = new BUserAccounts();
        }
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount != null) {
                Slog.d(TAG, "Account already exists in this virtual user");
                return false;
            }
            bAccount = accounts.addAccount(account);
            bAccount.password = password;
            if (extras != null) {
                for (String key : extras.keySet()) {
                    final String value = extras.getString(key);
                    bAccount.insertExtra(key, value);
                }
            }

            if (packageToVisibility != null) {
                for (Map.Entry<String, Integer> entry : packageToVisibility.entrySet()) {
                    setAccountVisibility(account, entry.getKey() ,
                            entry.getValue() ,
                            accounts);
                }
            }
        }
        saveAllAccounts();
        return true;
    }

    private boolean setAccountVisibility(Account account, String packageName, int newVisibility, BUserAccounts accounts) {
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount == null)
                return false;

            bAccount.visibility.put(packageName, newVisibility);
            return true;
        }
    }

    protected Account[] getAccountsFromCache(BUserAccounts userAccounts, String accountType, String callingPackage, boolean includeManagedNotVisible) {
        if (accountType != null) {
            Account[] accounts;
            synchronized (userAccounts.lock) {
                accounts = userAccounts.getAccountsByType(accountType);
            }
            if (accounts == null) {
                return EMPTY_ACCOUNT_ARRAY;
            } else {
                return filterAccounts(userAccounts, Arrays.copyOf(accounts, accounts.length), callingPackage, includeManagedNotVisible);
            }
        } else {
            // The former implementation combined every virtual user's account
            // database. Keep accounts isolated to the requested user.
            Account[] accountsArray;
            synchronized (userAccounts.lock) {
                accountsArray = userAccounts.toAccounts();
            }
            return filterAccounts(userAccounts, accountsArray, callingPackage,
                    includeManagedNotVisible);
        }
    }

    @NonNull
    private Account[] filterAccounts(BUserAccounts accounts, Account[] unfiltered, String callingPackage, boolean includeManagedNotVisible) {
        Map<Account, Integer> firstPass = new LinkedHashMap<>();
        for (Account account : unfiltered) {
            int visibility = resolveAccountVisibility(account, callingPackage, accounts);
            if ((visibility == AccountManager.VISIBILITY_VISIBLE
                    || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE)
                    || (includeManagedNotVisible
                    && (visibility
                    == AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE))) {
                firstPass.put(account, visibility);
            }
        }





        return firstPass.keySet().toArray(new Account[]{});
    }

    
    private Integer resolveAccountVisibility(Account account, @NonNull String packageName,
                                             BUserAccounts accounts) {
        if (accounts == null) {
            return AccountManager.VISIBILITY_NOT_VISIBLE;
        }
        BAccount bAccount = accounts.getAccount(account);
        if (bAccount == null) {
            return AccountManager.VISIBILITY_NOT_VISIBLE;
        }

        
        int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
        if (AccountManager.VISIBILITY_UNDEFINED != visibility) {
            return visibility;
        }
        // Only the installed authenticator and Google's actual core account
        // clients receive the default Google-account visibility needed for
        // first sign-in. Unrelated sandbox apps remain NOT_VISIBLE.
        if (mPms.isInstalled(packageName, accounts.userId)) {
            AuthenticatorInfo registered = mAuthenticatorCache.authenticators.get(account.type);
            if ((registered != null
                    && packageName.equals(registered.serviceInfo.packageName))
                    || GoogleAccountVisibilityPolicy.isTrustedGoogleClient(
                            account.type, packageName)) {
                return AccountManager.VISIBILITY_VISIBLE;
            }
        }
        return AccountManager.VISIBILITY_NOT_VISIBLE;
    }

    
    private int getAccountVisibilityFromCache(Account account, String packageName,
                                              BUserAccounts accounts) {
        synchronized (accounts.lock) {
            Map<String, Integer> accountVisibility =
                    getPackagesAndVisibilityForAccountLocked(account, accounts);
            Integer visibility = accountVisibility.get(packageName);
            return visibility != null ? visibility : AccountManager.VISIBILITY_UNDEFINED;
        }
    }

    private @NonNull
    Map<String, Integer> getPackagesAndVisibilityForAccountLocked(Account account,
                                                                  BUserAccounts accounts) {
        return accounts.getVisibility(account);
    }

    private void handleGetAccountsResult(
            IAccountManagerResponse response,
            Account[] accounts,
            String callingPackage,
            int userId) {

        if (needToStartChooseAccountActivity(accounts, callingPackage, userId)) {

            return;
        }
        if (accounts.length == 1) {
            Bundle bundle = new Bundle();
            bundle.putString(AccountManager.KEY_ACCOUNT_NAME, accounts[0].name);
            bundle.putString(AccountManager.KEY_ACCOUNT_TYPE, accounts[0].type);
            onResult(response, bundle);
            return;
        }
        
        onResult(response, new Bundle());
    }

    private boolean needToStartChooseAccountActivity(Account[] accounts, String callingPackage, int userId) {
        if (accounts.length < 1) return false;
        if (accounts.length > 1) return true;
        Account account = accounts[0];
        BUserAccounts userAccounts = getUserAccounts(userId);
        int visibility = resolveAccountVisibility(account, callingPackage, userAccounts);
        if (visibility == AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE) return true;
        return false;
    }

    private String readUserDataInternal(BUserAccounts accounts, Account account, String key) {
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            Map<String, String> accountUserData = accounts.getAccountUserData(account);
            return accountUserData.get(key);
        }
    }

    public String readPasswordInternal(BUserAccounts accounts, Account account) {
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount == null)
                return null;
            return bAccount.password;
        }
    }

    public BUserAccounts getUserAccounts(int userId) {
        synchronized (mUserAccountsMap) {
            BUserAccounts bUserAccounts = mUserAccountsMap.get(userId);
            if (bUserAccounts == null) {
                bUserAccounts = new BUserAccounts();
                bUserAccounts.userId = userId;
                mUserAccountsMap.put(userId, bUserAccounts);
            } else if (bUserAccounts.userId != userId) {
                // Repair stale database entries without moving any accounts
                // between virtual users.
                bUserAccounts.userId = userId;
            }
            return bUserAccounts;
        }
    }

    private boolean isAccountPresentForCaller(String accountName, String accountType, int userId) {
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (userAccounts != null) {
            BAccount account = userAccounts.getAccount(new Account(accountName, accountType));
            return account != null;
        }
        return false;
    }

    private boolean removeAccountInternal(BUserAccounts accounts, Account account) {
        synchronized (accounts.lock) {
            boolean del = accounts.delAccount(account);
            if (del) {
                saveAllAccounts();
            }
            return del;
        }
    }

    private class RemoveAccountSession extends Session {
        final Account mAccount;
        public RemoveAccountSession(BUserAccounts accounts, IAccountManagerResponse response,
                                    Account account, boolean expectActivityLaunch) {
            super(accounts, response, account.type, expectActivityLaunch,
                    true , account.name,
                    false );
            mAccount = account;
        }

        @Override
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", removeAccount"
                    + ", account " + mAccount;
        }

        @Override
        public void run() throws RemoteException {
            mAuthenticator.getAccountRemovalAllowed(this, mAccount);
        }

        @Override
        public void onResult(Bundle result) {
            if (result != null && result.containsKey(AccountManager.KEY_BOOLEAN_RESULT)
                    && !result.containsKey(AccountManager.KEY_INTENT)) {
                final boolean removalAllowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
                if (removalAllowed) {
                    removeAccountInternal(mAccounts, mAccount);
                }
                IAccountManagerResponse response = getResponseAndClose();
                if (response != null) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
                    }
                    try {
                        response.onResult(result);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error calling onResult()", e);
                    }
                }
            }
            super.onResult(result);
        }
    }

    private class GetAccountsByTypeAndFeatureSession extends Session {
        private final String[] mFeatures;
        private volatile Account[] mAccountsOfType = null;
        private volatile ArrayList<Account> mAccountsWithFeatures = null;
        private volatile int mCurrentAccount = 0;
        private final int mUserId;
        private final String mPackageName;
        private final boolean mIncludeManagedNotVisible;

        public GetAccountsByTypeAndFeatureSession(
                BUserAccounts accounts,
                IAccountManagerResponse response,
                String type,
                String[] features,
                int userId,
                String packageName,
                boolean includeManagedNotVisible) {
            super(accounts, response, type, false ,
                    true , null ,
                    false );
            mUserId = userId;
            mFeatures = features;
            mPackageName = packageName;
            mIncludeManagedNotVisible = includeManagedNotVisible;
        }

        @Override
        public void run() throws RemoteException {
            mAccountsOfType = getAccountsFromCache(mAccounts, mAccountType, mPackageName, mIncludeManagedNotVisible);
            
            mAccountsWithFeatures = new ArrayList<>(mAccountsOfType.length);
            mCurrentAccount = 0;

            checkAccount();
        }

        public void checkAccount() {
            if (mCurrentAccount >= mAccountsOfType.length) {
                sendResult();
                return;
            }

            final IAccountAuthenticator accountAuthenticator = mAuthenticator;
            if (accountAuthenticator == null) {
                
                
                
                
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "checkAccount: aborting session since we are no longer"
                            + " connected to the authenticator, " + toDebugString());
                }
                return;
            }
            try {
                accountAuthenticator.hasFeatures(this, mAccountsOfType[mCurrentAccount], mFeatures);
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception");
            }
        }

        @Override
        public void onResult(Bundle result) {
            mNumResults++;
            if (result == null) {
                onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "null bundle");
                return;
            }
            if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                mAccountsWithFeatures.add(mAccountsOfType[mCurrentAccount]);
            }
            mCurrentAccount++;
            checkAccount();
        }

        public void sendResult() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    Account[] accounts = new Account[mAccountsWithFeatures.size()];
                    for (int i = 0; i < accounts.length; i++) {
                        accounts[i] = mAccountsWithFeatures.get(i);
                    }
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
                    }
                    Bundle result = new Bundle();
                    result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
                    response.onResult(result);
                } catch (RemoteException e) {
                    
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        @Override
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", getAccountsByTypeAndFeatures"
                    + ", " + (mFeatures != null ? TextUtils.join(",", mFeatures) : null);
        }
    }

    private static final class AuthenticatorInfo {
        final AuthenticatorDescription desc;
        final ServiceInfo serviceInfo;

        AuthenticatorInfo(AuthenticatorDescription desc, ServiceInfo info) {
            this.desc = desc;
            this.serviceInfo = info;
        }
    }

    private static final class AuthenticatorCache {
        final AuthenticatorRegistry<AuthenticatorInfo> registry = new AuthenticatorRegistry<>();
        volatile Map<String, AuthenticatorInfo> authenticators = registry.snapshot();

        void publish(Map<String, AuthenticatorInfo> refreshed) {
            registry.replaceAll(refreshed);
            authenticators = registry.snapshot();
        }
    }

    private static final String ANDROID_XML_NS = "http://schemas.android.com/apk/res/android";

    private static AuthenticatorDescription parseAuthenticatorDescription(Resources resources,
            String packageName, AttributeSet attributes) {
        // Account-authenticator metadata is ordinary public Android XML.
        // Avoid reflecting hidden com.android.internal.R.styleable fields;
        // those can fail on recent Android versions and leave an empty
        // authenticator registry even though Google services are installed.
        if (resources == null || attributes == null) return null;
        String accountType = attributes.getAttributeValue(ANDROID_XML_NS, "accountType");
        int accountTypeRes = attributes.getAttributeResourceValue(
                ANDROID_XML_NS, "accountType", 0);
        if (accountTypeRes != 0) {
            try {
                accountType = resources.getString(accountTypeRes);
            } catch (Resources.NotFoundException e) {
                Slog.w(TAG, "Authenticator accountType string resource unavailable");
                return null;
            }
        }
        if (TextUtils.isEmpty(accountType)) return null;
        return new AuthenticatorDescription(
                accountType,
                packageName,
                attributes.getAttributeResourceValue(ANDROID_XML_NS, "label", 0),
                attributes.getAttributeResourceValue(ANDROID_XML_NS, "icon", 0),
                attributes.getAttributeResourceValue(ANDROID_XML_NS, "smallIcon", 0),
                attributes.getAttributeResourceValue(ANDROID_XML_NS, "accountPreferences", 0),
                attributes.getAttributeBooleanValue(ANDROID_XML_NS, "customTokens", false));
    }

    public void loadAuthenticatorCache(String packageName) {
        // A package installation must NOT remove the authenticators registered
        // by previously installed packages (especially com.google).
        // Rebuilding from every virtual package also handles updates/removals.
        Intent intent = new Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT);
        Map<String, AuthenticatorInfo> refreshed = new HashMap<>();
        generateServicesMap(
                mPms.queryIntentServices(intent, PackageManager.GET_META_DATA, BUserHandle.USER_ALL),
                refreshed, new RegisteredServicesParser());
        mAuthenticatorCache.publish(refreshed);
        Slog.d(TAG, "Virtual authenticator types available: " + refreshed.size());
    }

    private void generateServicesMap(List<ResolveInfo> services, Map<String, AuthenticatorInfo> map,
                                     RegisteredServicesParser accountParser) {
        if (services == null) return;
        for (ResolveInfo info : services) {
            if (info == null || info.serviceInfo == null
                    || info.serviceInfo.applicationInfo == null) continue;
            XmlResourceParser parser = accountParser.getParser(mContext, info.serviceInfo,
                    AccountManager.AUTHENTICATOR_META_DATA_NAME);
            if (parser == null) continue;
            try {
                AttributeSet attributeSet = Xml.asAttributeSet(parser);
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && type != XmlPullParser.START_TAG) { }
                if (type == XmlPullParser.START_TAG
                        && AccountManager.AUTHENTICATOR_ATTRIBUTES_NAME.equals(parser.getName())) {
                    AuthenticatorDescription desc = parseAuthenticatorDescription(
                            accountParser.getResources(mContext, info.serviceInfo.applicationInfo),
                            info.serviceInfo.packageName, attributeSet);
                    if (desc != null) {
                        map.put(desc.type, new AuthenticatorInfo(desc, info.serviceInfo));
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Could not read virtual account authenticator metadata");
            } finally {
                parser.close();
            }
        }
    }

    private abstract class Session extends IAccountAuthenticatorResponse.Stub
            implements IBinder.DeathRecipient, ServiceConnection {
        IAccountManagerResponse mResponse;
        final String mAccountType;
        final boolean mExpectActivityLaunch;
        final long mCreationTime;
        final String mAccountName;
        
        final boolean mAuthDetailsRequired;
        
        
        
        
        final boolean mUpdateLastAuthenticatedTime;

        public int mNumResults = 0;
        private int mNumRequestContinued = 0;
        private int mNumErrors = 0;

        IAccountAuthenticator mAuthenticator = null;
        private boolean mServiceBound = false;

        private final boolean mStripAuthTokenFromResult;
        protected final BUserAccounts mAccounts;

        public Session(BUserAccounts accounts, IAccountManagerResponse response, String accountType,
                       boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                       boolean authDetailsRequired) {
            this(accounts, response, accountType, expectActivityLaunch, stripAuthTokenFromResult,
                    accountName, authDetailsRequired, false );
        }

        public Session(BUserAccounts accounts, IAccountManagerResponse response, String accountType,
                       boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                       boolean authDetailsRequired, boolean updateLastAuthenticatedTime) {
            super();
            
            if (accountType == null) throw new IllegalArgumentException("accountType is null");
            mAccounts = accounts;
            mStripAuthTokenFromResult = stripAuthTokenFromResult;
            mResponse = response;
            mAccountType = accountType;
            mExpectActivityLaunch = expectActivityLaunch;
            mCreationTime = SystemClock.elapsedRealtime();
            mAccountName = accountName;
            mAuthDetailsRequired = authDetailsRequired;
            mUpdateLastAuthenticatedTime = updateLastAuthenticatedTime;

            synchronized (mSessions) {
                mSessions.put(toString(), this);
            }
            if (response != null) {
                try {
                    response.asBinder().linkToDeath(this, 0 );
                } catch (RemoteException e) {
                    mResponse = null;
                    binderDied();
                }
            }
        }

        IAccountManagerResponse getResponseAndClose() {
            if (mResponse == null) {
                
                return null;
            }
            IAccountManagerResponse response = mResponse;
            close(); 
            return response;
        }

        
        protected boolean checkKeyIntent(int authUid, Intent intent) {
            
            
            // Do not add a synthetic empty ClipData item to Google's
            // authentication Intent: on some Android versions it is invalid.
            intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            long bid = Binder.clearCallingIdentity();
            try {
                ResolveInfo resolveInfo = mPms.resolveActivity(intent, 0, null, mAccounts.userId);
                if (resolveInfo == null) {
                    return false;
                }
                
                return true;
            } finally {
                Binder.restoreCallingIdentity(bid);
            }
        }

        private void close() {
            synchronized (mSessions) {
                if (mSessions.remove(toString()) == null) {
                    
                    return;
                }
            }
            if (mResponse != null) {
                
                mResponse.asBinder().unlinkToDeath(this, 0 );

                
                mResponse = null;
            }
            cancelTimeout();
            unbind();
        }

        @Override
        public void binderDied() {
            mResponse = null;
            close();
        }

        protected String toDebugString() {
            return toDebugString(SystemClock.elapsedRealtime());
        }

        protected String toDebugString(long now) {
            return "Session: expectLaunch " + mExpectActivityLaunch
                    + ", connected " + (mAuthenticator != null)
                    + ", stats (" + mNumResults + "/" + mNumRequestContinued
                    + "/" + mNumErrors + ")"
                    + ", lifetime " + ((now - mCreationTime) / 1000.0);
        }

        void bind() {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "initiating bind to authenticator type " + mAccountType);
            }
            if (!bindToAuthenticator(mAccountType)) {
                Log.d(TAG, "bind attempt failed for " + toDebugString());
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "bind failure");
            }
        }

        private void unbind() {
            mAuthenticator = null;
            if (mServiceBound) {
                mServiceBound = false;
                try {
                    mContext.unbindService(this);
                } catch (IllegalArgumentException ignored) {
                    // Android may have dropped the connection while shutting
                    // down the virtual authenticator process.
                }
            }
        }

        public void cancelTimeout() {
            mHandler.removeMessages(MESSAGE_TIMED_OUT, this);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAuthenticator = IAccountAuthenticator.Stub.asInterface(service);
            try {
                run();
            } catch (RemoteException e) {
                onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                        "remote exception");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAuthenticator = null;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "disconnected");
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Session.onServiceDisconnected: "
                                + "caught RemoteException while responding", e);
                    }
                }
            }
        }

        public abstract void run() throws RemoteException;

        public void onTimedOut() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "timeout");
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Session.onTimedOut: caught RemoteException while responding",
                                e);
                    }
                }
            }
        }

        @Override
        public void onResult(Bundle result) {
            mNumResults++;
            Intent intent = null;
            if (result != null) {
                boolean isSuccessfulConfirmCreds = result.getBoolean(
                        AccountManager.KEY_BOOLEAN_RESULT, false);
                boolean isSuccessfulUpdateCredsOrAddAccount =
                        result.containsKey(AccountManager.KEY_ACCOUNT_NAME)
                                && result.containsKey(AccountManager.KEY_ACCOUNT_TYPE);
                
                
                
                // Persist a completed provider-verified account-creation result
                // for this virtual user. Never store an intermediary login
                // Intent as though the sign-in had succeeded.
                String returnedName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                String returnedType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                if (mUpdateLastAuthenticatedTime && TextUtils.isEmpty(mAccountName)
                        && AccountResponsePolicy.isCompletedAccountAdd(
                                mAccountType, returnedName, returnedType,
                                result.containsKey(AccountManager.KEY_INTENT))) {
                    Account created = new Account(returnedName, returnedType);
                    if (!isAccountPresentForCaller(returnedName, returnedType,
                            mAccounts.userId)) {
                        addAccountInternal(mAccounts, created, null, null, null);
                    }
                }
                boolean needUpdate = mUpdateLastAuthenticatedTime
                        && (isSuccessfulConfirmCreds || isSuccessfulUpdateCredsOrAddAccount);
                if (needUpdate || mAuthDetailsRequired) {
                    // For addAccount() the session starts WITHOUT an account
                    // name. Using mAccountName directly threw IllegalArgumentException
                    // after a successful authentication response.
                    String resolvedName = !TextUtils.isEmpty(mAccountName)
                            ? mAccountName : result.getString(AccountManager.KEY_ACCOUNT_NAME);
                    boolean accountPresent = !TextUtils.isEmpty(resolvedName)
                            && isAccountPresentForCaller(resolvedName, mAccountType, mAccounts.userId);
                    if (needUpdate && accountPresent) {
                        updateLastAuthenticatedTime(mAccounts, new Account(resolvedName, mAccountType));
                    }
                    if (mAuthDetailsRequired) {
                        long lastAuthenticatedTime = -1;
                        if (accountPresent) {
                            lastAuthenticatedTime = mAccounts
                                    .findAccountLastAuthenticatedTime(
                                            new Account(resolvedName, mAccountType));
                        }
                        result.putLong(AccountManager.KEY_LAST_AUTHENTICATED_TIME,
                                lastAuthenticatedTime);
                    }
                }
            }
            if (result != null
                    && (intent = result.getParcelable(AccountManager.KEY_INTENT)) != null) {
                if (!checkKeyIntent(
                        Binder.getCallingUid(),
                        intent)) {
                    onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "invalid intent in bundle returned");
                    return;
                }
            }
            if (result != null
                    && !TextUtils.isEmpty(result.getString(AccountManager.KEY_AUTHTOKEN))) {
                String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    Account account = new Account(accountName, accountType);


                }
            }
            IAccountManagerResponse response;
            if (mExpectActivityLaunch && result != null
                    && result.containsKey(AccountManager.KEY_INTENT)) {
                response = mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response != null) {
                try {
                    if (result == null) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, getClass().getSimpleName()
                                    + " calling onError() on response " + response);
                        }
                        response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle returned");
                    } else {
                        if (mStripAuthTokenFromResult) {
                            result.remove(AccountManager.KEY_AUTHTOKEN);
                        }
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, getClass().getSimpleName()
                                    + " calling onResult() on response " + response);
                        }
                        if ((result.getInt(AccountManager.KEY_ERROR_CODE, -1) > 0) &&
                                (intent == null)) {
                            
                            response.onError(result.getInt(AccountManager.KEY_ERROR_CODE),
                                    result.getString(AccountManager.KEY_ERROR_MESSAGE));
                        } else {
                            response.onResult(result);
                        }
                    }
                } catch (RemoteException e) {
                    
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        @Override
        public void onRequestContinued() {
            mNumRequestContinued++;
        }

        @Override
        public void onError(int errorCode, String errorMessage) {
            mNumErrors++;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, getClass().getSimpleName()
                            + " calling onError() on response " + response);
                }
                try {
                    response.onError(errorCode, errorMessage);
                } catch (RemoteException e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Session.onError: caught RemoteException while responding", e);
                    }
                }
            } else {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Session.onError: already closed");
                }
            }
        }

        
        private boolean bindToAuthenticator(String authenticatorType) {
            AuthenticatorInfo provider = mAuthenticatorCache.authenticators.get(authenticatorType);
            if (provider == null) {
                Slog.w(TAG, "No virtual authenticator for type: " + authenticatorType);
                return false;
            }
            if (!mPms.isInstalled(provider.serviceInfo.packageName, mAccounts.userId)) {
                Slog.w(TAG, "Authenticator package is not installed in the requested virtual user.");
                return false;
            }

            // This service runs in HiddenBox's host process. Directly calling
            // mContext.bindService() on the original component would bind the
            // *real phone's* authenticator (if any), not the virtual one.
            // Bind through BlackBox's existing proxy service dispatcher
            // instead, so the provider runs in its own virtual user context.
            Intent virtualService = new Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT);
            ComponentName virtualComponent = new ComponentName(
                    provider.serviceInfo.packageName, provider.serviceInfo.name);
            virtualService.setComponent(virtualComponent);
            virtualService.putExtra("_B_|_UserId", mAccounts.userId);

            try {
                Intent proxyIntent = BActivityManagerService.get().bindService(
                        virtualService, null, null, mAccounts.userId);
                if (proxyIntent == null || proxyIntent.getComponent() == null
                        || !BlackBoxCore.getHostPkg().equals(
                                proxyIntent.getComponent().getPackageName())
                        || virtualComponent.equals(proxyIntent.getComponent())) {
                    Slog.w(TAG, "Virtual account authenticator proxy is unavailable.");
                    return false;
                }

                mServiceBound = mContext.bindService(
                        proxyIntent, this, Context.BIND_AUTO_CREATE);
                if (!mServiceBound) {
                    Slog.w(TAG, "Android did not bind the virtual authenticator proxy.");
                }
                return mServiceBound;
            } catch (Exception e) {
                // No account passwords, login options, tokens or email
                // addresses should ever enter these diagnostic messages.
                Slog.e(TAG, "Virtual authenticator binding failed: "
                        + e.getClass().getSimpleName());
                return false;
            }
        }
    }

    private void onResult(IAccountManagerResponse response, Bundle result) {
        if (result == null) {
            Log.e(TAG, "the result is unexpectedly null", new Exception());
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                    + response);
        }
        try {
            response.onResult(result);
        } catch (RemoteException e) {
            
            
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "failure while notifying response", e);
            }
        }
    }


    private boolean updateLastAuthenticatedTime(BUserAccounts userAccounts, Account account) {
        userAccounts.updateLastAuthenticatedTime(account);
        return true;
    }

    private String getCallingPackageName() {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            throw new IllegalArgumentException("ProcessRecord is null, PID: " + callingPid);
        return processByPid.getPackageName();
    }
}

#!/usr/bin/env python3
"""Execute actual IAccountManagerProxy code using isolated JVM-only fixtures.

This verifies the Java method bridge, not Android Binder, Google login or KAST.
No fixture classes are installed in the application. Requires a JDK on PATH.
"""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

PREFIX = 'top/niunaijun/blackbox'

def run(proxy_file: Path, manager_file: Path) -> int:
    with tempfile.TemporaryDirectory(prefix='hiddenbox-proxy-contract-') as temp:
        root = Path(temp)
        def put(name, text):
            p = root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding='utf-8')
        put(PREFIX + '/fake/service/IAccountManagerProxy.java', proxy_file.read_text())
        fixtures = {
            'android/accounts/Account.java': 'package android.accounts; public class Account {}',
            'android/accounts/IAccountManagerResponse.java': 'package android.accounts; public interface IAccountManagerResponse {}',
            'android/content/Context.java': 'package android.content; public class Context { public static final String ACCOUNT_SERVICE = "account"; }',
            'android/os/Bundle.java': 'package android.os; public class Bundle {}',
            'black/android/accounts/BRIAccountManagerStub.java': 'package black.android.accounts; public class BRIAccountManagerStub { public static BRIAccountManagerStub get(){return new BRIAccountManagerStub();} public Object asInterface(Object b){return b;} }',
            'black/android/os/BRServiceManager.java': 'package black.android.os; public class BRServiceManager { public static BRServiceManager get(){return new BRServiceManager();} public Object getService(String n){return null;} }',
            PREFIX + '/fake/hook/MethodHook.java': 'package top.niunaijun.blackbox.fake.hook; import java.lang.reflect.Method; public abstract class MethodHook { protected abstract Object hook(Object who, Method method, Object[] args) throws Throwable; }',
            PREFIX + '/fake/hook/ProxyMethod.java': 'package top.niunaijun.blackbox.fake.hook; public @interface ProxyMethod { String value(); }',
            PREFIX + '/fake/hook/BinderInvocationStub.java': '''package top.niunaijun.blackbox.fake.hook;
import java.lang.reflect.Method;
public abstract class BinderInvocationStub {
    protected BinderInvocationStub(Object o) {}
    protected abstract Object getWho();
    protected abstract void inject(Object base, Object proxy);
    protected void replaceSystemService(String name) {}
    protected void onBindMethod() {}
    public abstract boolean isBadEnv();
    public Object invoke(Object p, Method m, Object[] a) throws Throwable { return null; }
}''',
            PREFIX + '/utils/Slog.java': 'package top.niunaijun.blackbox.utils; public class Slog { public static void d(String a,String b) {} }',
        }
        for name, content in fixtures.items():
            put(name, content)
        # Generate only the public facade signatures. Its bodies are deliberately
        # test fixtures; the proxy under test is copied verbatim from production.
        signatures = re.findall(r'public\s+([\w<>\[\]]+)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws\s+[^{}]+)?\s*\{', manager_file.read_text())
        methods = []
        for result, name, params in signatures:
            args = [p.strip().split()[-1] for p in params.split(',') if p.strip()]
            body = 'calls++; lastArgs = new Object[]{' + ','.join(args) + '};'
            if result == 'boolean': body += 'return booleanResult;'
            elif result == 'int': body += 'return 3;'
            elif result != 'void': body += 'return null;'
            methods.append('public ' + result + ' ' + name + '(' + params + ') {' + body + '}')
        put(PREFIX + '/fake/frameworks/BAccountManager.java', '''package top.niunaijun.blackbox.fake.frameworks;
import android.accounts.*;
import android.os.Bundle;
import java.util.Map;
public class BAccountManager {
    private static final BAccountManager INSTANCE = new BAccountManager();
    public static boolean booleanResult;
    public static int calls;
    public static Object[] lastArgs;
    public static BAccountManager get() { return INSTANCE; }
''' + '\n'.join(methods) + '\n}')
        put('android/accounts/AuthenticatorDescription.java', 'package android.accounts; public class AuthenticatorDescription {}')
        put('ContractHarness.java', '''import java.lang.reflect.*;
import java.util.*;
import android.accounts.Account;
import android.accounts.IAccountManagerResponse;
import android.os.Bundle;
import top.niunaijun.blackbox.fake.frameworks.BAccountManager;

public class ContractHarness {
    public interface BooleanApi { boolean call(); }
    private static Object runHook(String name, Object[] args) throws Throwable {
        Class<?> clazz = Class.forName("top.niunaijun.blackbox.fake.service.IAccountManagerProxy$" + name);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method hook = clazz.getDeclaredMethod("hook", Object.class, Method.class, Object[].class);
        hook.setAccessible(true);
        try { return hook.invoke(instance, null, BooleanApi.class.getMethod("call"), args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    public static void main(String[] args) throws Throwable {
        Account account = new Account();
        Bundle bundle = new Bundle();
        String[] types = {"synthetic.test"};
        Map<String, Object[]> booleans = new LinkedHashMap<>();
        booleans.put("accountAuthenticated", new Object[]{account});
        booleans.put("addAccountExplicitly", new Object[]{account, null, bundle});
        booleans.put("removeAccountExplicitly", new Object[]{account});
        booleans.put("addAccountExplicitlyWithVisibility", new Object[]{account, null, bundle, new HashMap<>()});
        booleans.put("setAccountVisibility", new Object[]{account, "synthetic.test", 1});
        int failed = 0;
        int passed = 0;
        for (Map.Entry<String, Object[]> c : booleans.entrySet()) {
            for (boolean value : new boolean[]{true, false}) {
                BAccountManager.booleanResult = value;
                BAccountManager.calls = 0;
                BAccountManager.lastArgs = null;
                BooleanApi bridge = (BooleanApi) Proxy.newProxyInstance(
                        BooleanApi.class.getClassLoader(), new Class[]{BooleanApi.class},
                        (p, m, a) -> runHook(c.getKey(), c.getValue()));
                try {
                    boolean actual = bridge.call();
                    if (actual != value || BAccountManager.calls != 1
                            || !Arrays.equals(c.getValue(), BAccountManager.lastArgs)) {
                        throw new AssertionError("Facade result or arguments changed");
                    }
                    System.out.println("PASS " + c.getKey() + " delegate=" + value);
                    passed++;
                } catch (Throwable error) {
                    System.out.println("FAIL " + c.getKey() + " delegate=" + value + " -> " + error.getClass().getSimpleName());
                    failed++;
                }
            }
        }
        for (String method : new String[]{"addAccount", "addAccountAsUser"}) {
            BAccountManager.calls = 0;
            IAccountManagerResponse callback = new IAccountManagerResponse(){};
            Object[] callArgs = {callback, "synthetic.test", null, types, true, bundle};
            runHook(method, callArgs);
            if (BAccountManager.calls != 1 || !Arrays.equals(callArgs, BAccountManager.lastArgs)) {
                throw new AssertionError("Account callback was not forwarded exactly once");
            }
            System.out.println("PASS " + method + " callback delegation");
            passed++;
        }
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) System.exit(1);
    }
}''')
        files = sorted(str(p) for p in root.rglob('*.java'))
        subprocess.run(['javac', '-d', str(root/'classes'), *files], check=True)
        return subprocess.run(['java', '-cp', str(root/'classes'), 'ContractHarness']).returncode

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('proxy', type=Path)
    p.add_argument('manager', type=Path)
    opts = p.parse_args()
    raise SystemExit(run(opts.proxy, opts.manager))

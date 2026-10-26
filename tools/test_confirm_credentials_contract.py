#!/usr/bin/env python3
"""Check the verbatim production confirmCredentialsAsUser method in a JVM harness.

No real Android Binder, Google account, login UI, Play Integrity or KAST is
involved. This test verifies only session creation and delegation.
"""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

def extract(src):
    rx = r'public\s+void\s+confirmCredentialsAsUser\s*\([^)]*\)\s*throws\s+RemoteException\s*\{'
    m = list(re.finditer(rx, src))
    if len(m) != 1:
        raise ValueError(f'Expected one method, found {len(m)}')
    start, depth = m[0].start(), 0
    for i in range(m[0].end()-1, len(src)):
        if src[i] == '{': depth += 1
        if src[i] == '}':
            depth -= 1
            if depth == 0: return src[start:i+1]
    raise ValueError('Unbalanced method')

HARNESS = r'''
public class ConfirmContract {
  public static class RemoteException extends Exception {}
  public interface IAccountManagerResponse {}
  public static class Response implements IAccountManagerResponse {}
  public static class Bundle {}
  public static class Account {
    final String name, type;
    Account(String name,String type){this.name=name;this.type=type;}
  }
  public static class BUserAccounts {
    final int userId;
    BUserAccounts(int id){userId=id;}
  }
  public static class Authenticator {
    int calls;
    Account account;
    Bundle options;
    void confirmCredentials(Service.Session session,Account a,Bundle b){
      calls++;account=a;options=b;
    }
  }
  public static class Service {
    final Authenticator auth=new Authenticator();
    int seenUser=-1,bounds;
    String name,type;
    boolean expect,strip,details,update;
    IAccountManagerResponse response;
    BUserAccounts getUserAccounts(int id){seenUser=id;return new BUserAccounts(id);}
    abstract class Session {
      final Authenticator mAuthenticator=auth;
      Session(BUserAccounts user,IAccountManagerResponse r,String t,boolean e,
          boolean s,String n,boolean d,boolean u) {
        if(user.userId!=seenUser)throw new AssertionError("user mismatch");
        response=r;name=n;type=t;expect=e;strip=s;details=d;update=u;
      }
      public abstract void run() throws RemoteException;
      void bind() throws RemoteException {bounds++;run();}
    }
    __PRODUCTION_METHOD__
  }
  static void ok(boolean v,String message){if(!v)throw new AssertionError(message);}
  static void nullChecks() throws RemoteException {
    Service s=new Service();boolean r=false,a=false;
    try{s.confirmCredentialsAsUser(null,new Account("fake","test"),new Bundle(),true,9);}
    catch(IllegalArgumentException e){r=true;}
    try{s.confirmCredentialsAsUser(new Response(),null,new Bundle(),true,9);}
    catch(IllegalArgumentException e){a=true;}
    ok(r && a,"missing required argument silently accepted");
    System.out.println("PASS rejected missing response and account");
  }
  static void route(int id,boolean expect,Bundle options) throws RemoteException {
    Service s=new Service();Response r=new Response();
    Account a=new Account("synthetic","synthetic.type");
    s.confirmCredentialsAsUser(r,a,options,expect,id);
    ok(s.seenUser==id && s.bounds==1 && s.auth.calls==1,"not bound to virtual authenticator");
    ok(s.auth.account==a && s.auth.options==options,"provider argument mutated");
    ok(s.response==r && a.name.equals(s.name) && a.type.equals(s.type),"session identity changed");
    ok(s.expect==expect && s.strip && s.details && s.update,"session flags incorrect");
    System.out.println("PASS user="+id+" expect="+expect+" nullOptions="+(options==null));
  }
  public static void main(String[] args) throws Exception {
    nullChecks();route(3,true,new Bundle());route(7,false,null);
    System.out.println("RESULT passed=3 failed=0");
  }
}
'''
def main(path):
    method=extract(path.read_text(encoding='utf-8'))
    with tempfile.TemporaryDirectory(prefix='confirm-contract-') as tmp:
        src=Path(tmp)/'ConfirmContract.java'
        src.write_text(HARNESS.replace('__PRODUCTION_METHOD__',method),encoding='utf-8')
        subprocess.run(['javac','-d',tmp,str(src)],check=True)
        return subprocess.run(['java','-cp',tmp,'ConfirmContract']).returncode

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('file',type=Path)
    args=p.parse_args()
    raise SystemExit(main(args.file))

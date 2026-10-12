import { createContext,useCallback,useContext,useEffect,type ReactNode } from 'react';
import { useQuery,useQueryClient } from '@tanstack/react-query';
import { api,ApiError } from '../shared/api';
import type { User } from '../shared/types';
const AuthContext=createContext<{user:User|null;loading:boolean;refresh:()=>Promise<User>;signOut:()=>Promise<void>}>({
  user:null,loading:true,refresh:async()=>{throw new Error('登录上下文尚未初始化')},signOut:async()=>{},
});
export function AuthProvider({children}:{children:ReactNode}) {
  const client=useQueryClient();
  const me=useQuery({queryKey:['me'],queryFn:async({signal})=>{
    try{return await api<User>('/auth/me',{signal})}
    catch(error){if(error instanceof ApiError&&error.status===401)return null;throw error}
  },retry:false});

  const clearSession=useCallback(async()=>{
    await client.cancelQueries();
    // Keep the observed auth query attached to the provider when accounts change.
    client.removeQueries({predicate:query=>query.queryKey[0]!=='me'});
    client.setQueryData(['me'],null);
  },[client]);

  useEffect(()=>{
    const expired=()=>{void clearSession()};
    window.addEventListener('auth-expired',expired);
    return()=>window.removeEventListener('auth-expired',expired);
  },[clearSession]);

  const refresh=async()=>{
    await client.cancelQueries();
    client.removeQueries({predicate:query=>query.queryKey[0]!=='me'});
    const result=await me.refetch({throwOnError:true});
    if(!result.data)throw new ApiError(401,'UNAUTHENTICATED','登录状态未建立，请重新登录');
    return result.data;
  };
  const signOut=async()=>{
    try{await api('/auth/logout',{method:'POST'})}
    catch(error){if(!(error instanceof ApiError&&error.status===401))throw error}
    await clearSession();
  };

  return <AuthContext.Provider value={{user:me.data??null,loading:me.isPending,refresh,signOut}}>{children}</AuthContext.Provider>;
}
export const useAuth=()=>useContext(AuthContext);

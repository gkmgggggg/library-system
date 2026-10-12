import { createContext,useContext,useEffect,type ReactNode } from 'react';
import { useQuery,useQueryClient } from '@tanstack/react-query';
import { api,ApiError } from '../shared/api';
import type { User } from '../shared/types';
const AuthContext=createContext<{user:User|null;loading:boolean;refresh:()=>Promise<unknown>;signOut:()=>Promise<void>}>({user:null,loading:true,refresh:async()=>{},signOut:async()=>{}});
export function AuthProvider({children}:{children:ReactNode}) {
  const client=useQueryClient();
  const me=useQuery({queryKey:['me'],queryFn:async()=>{try{return await api<User>('/auth/me')}catch(e){if(e instanceof ApiError&&e.status===401)return null;throw e}},retry:false});
  const clear=()=>{client.clear();client.setQueryData(['me'],null)};
  useEffect(()=>{window.addEventListener('auth-expired',clear);return()=>window.removeEventListener('auth-expired',clear)},[client]);
  return <AuthContext.Provider value={{user:me.data??null,loading:me.isPending,refresh:async()=>{client.clear();return client.fetchQuery({queryKey:['me'],queryFn:()=>api<User>('/auth/me')})},signOut:async()=>{await api('/auth/logout',{method:'POST'});clear()}}}>{children}</AuthContext.Provider>;
}
export const useAuth=()=>useContext(AuthContext);

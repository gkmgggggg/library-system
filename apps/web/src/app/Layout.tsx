import { Outlet,NavLink,useNavigate } from 'react-router-dom';
import { Button,Dropdown,App } from 'antd';
import { BookOutlined,DashboardOutlined,SwapOutlined,TeamOutlined,TagsOutlined,DatabaseOutlined,HistoryOutlined,UserOutlined,MenuOutlined } from '@ant-design/icons';
import { useAuth } from './auth';
import { useState } from 'react';
export default function Layout() {
  const {user,signOut}=useAuth();const navigate=useNavigate();const {message}=App.useApp();const [open,setOpen]=useState(false);
  const nav=user?.role==='admin'?[
    ['/',<DashboardOutlined/>,'工作概览'],['/books',<BookOutlined/>,'图书目录'],['/copies',<DatabaseOutlined/>,'馆藏副本'],['/circulation',<SwapOutlined/>,'借还工作台'],['/loans',<HistoryOutlined/>,'借阅记录'],['/readers',<TeamOutlined/>,'读者管理'],['/categories',<TagsOutlined/>,'分类管理']
  ]:[['/books',<BookOutlined/>,'图书目录'],['/loans',<HistoryOutlined/>,'我的借阅']];
  return <div className="shell"><aside className={open?'sidebar open':'sidebar'}><NavLink to="/" className="brand"><span className="brand-symbol">册</span><div>书间<small>LIBRARY DESK</small></div></NavLink><div className="nav-label">{user?.role==='admin'?'图书室管理':'读者空间'}</div><nav>{nav.map(([path,icon,label])=><NavLink key={path as string} to={path as string} end onClick={()=>setOpen(false)}>{icon}<span>{label}</span></NavLink>)}</nav><div className="sidebar-note"><span>一本书，一次相遇。</span><small>借阅上限 5 册 · 借期 30 天</small></div></aside><div className="workspace"><header className="topbar"><div><Button className="menu-toggle" icon={<MenuOutlined/>} aria-label="切换导航" onClick={()=>setOpen(!open)}/><span className="topbar-label">{user?.role==='admin'?'图书室 / 管理工作区':'图书室 / 阅读空间'}</span></div><Dropdown menu={{items:[{key:'password',label:'修改密码'},{key:'logout',label:'退出登录'}],onClick:async({key})=>{if(key==='password')navigate('/change-password');else try{await signOut()}catch(e){message.error((e as Error).message)}}}}><Button type="text" icon={<UserOutlined/>}>{user?.username}<span className="role-label">{user?.role==='admin'?'管理员':'读者'}</span></Button></Dropdown></header><main><Outlet/></main><footer>书间 · 图书借阅管理<span>让流通有序，让阅读继续。</span></footer></div></div>
}

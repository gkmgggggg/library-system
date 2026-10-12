import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Form,Select,Button,Input,Modal,Descriptions,Tabs,Alert } from 'antd';
import { SwapOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { api,query } from '../../shared/api';
import { useAction } from '../../shared/useAction';
import { PageTitle } from '../../shared/ui';
import type { Reader,Copy,Page } from '../../shared/types';
import { LoanList } from '../loans/LoansPage';
function BorrowDesk() {
  const [readerSearch,setReaderSearch]=useState('');const [copySearch,setCopySearch]=useState('');const [confirmation,setConfirmation]=useState<{readerId:string;copyId:string;note?:string}|null>(null);
  const [form]=Form.useForm();const {run,busy}=useAction();
  const readers=useQuery({queryKey:['readers','search',readerSearch],queryFn:({signal})=>api<Page<Reader>>('/readers'+query({q:readerSearch}),{signal})});
  const copies=useQuery({queryKey:['copies','search',copySearch],queryFn:({signal})=>api<Page<Copy>>('/copies'+query({q:copySearch}),{signal})});
  const reader=readers.data?.items.find(r=>r.id===confirmation?.readerId);const copy=copies.data?.items.find(c=>c.id===confirmation?.copyId);
  const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  return <div className="desk-grid"><div className="panel borrow-panel"><span className="eyebrow">CIRCULATION / 借出</span><h2>办理新借阅</h2><p className="muted">先确认读者，再核对实物副本编号。</p><Form layout="vertical" form={form} onFinish={setConfirmation}><Form.Item name="readerId" label="01 · 选择读者" rules={[{required:true,message:'请选择读者'}]}><Select showSearch filterOption={false} onSearch={setReaderSearch} loading={readers.isPending} placeholder="输入姓名或读者编号查找" options={readers.data?.items.map(r=>({value:r.id,label:`${r.name} · ${r.code} · 已借 ${r.activeLoans}/5 册`,disabled:!r.enabled||r.activeLoans>=5}))}/></Form.Item><Form.Item name="copyId" label="02 · 选择馆藏副本" rules={[{required:true,message:'请选择副本'}]}><Select showSearch filterOption={false} onSearch={setCopySearch} loading={copies.isPending} placeholder="输入馆藏编号或书名查找" options={copies.data?.items.map(c=>({value:c.id,label:`${c.code} · ${c.title}`,disabled:c.status!=='available'}))}/></Form.Item><Form.Item name="note" label="借阅备注"><Input.TextArea rows={3} maxLength={500}/></Form.Item><Button type="primary" htmlType="submit" icon={<SwapOutlined/>} size="large" block loading={busy}>核对并办理借阅</Button></Form>{readers.error||copies.error?<Alert type="error" title={(readers.error||copies.error)?.message} style={{marginTop:16}}/>:null}</div><div className="desk-guide"><span className="eyebrow">BORROWING POLICY</span><h2>借阅规则</h2><div className="policy-number">5 <small>册</small></div><p>每位读者同时最多借阅</p><div className="policy-row"><strong>30 天</strong><span>借阅期限</span></div><div className="policy-row"><strong>1 次</strong><span>可续借次数，延长 30 天</span></div><p className="guide-note">有逾期未还图书时暂停新借阅。借出与归还由管理员核验实物后办理。</p></div><Modal title="确认借阅信息" open={!!confirmation} onCancel={()=>setConfirmation(null)} onOk={()=>confirmation&&run('/loans',confirmation,'POST',()=>{setConfirmation(null);form.resetFields();setCopySearch('');setReaderSearch('')})} confirmLoading={busy} okText="确认借出"><Descriptions column={1} items={[{key:'reader',label:'读者',children:reader?`${reader.name} · ${reader.code}`:'已选读者'},{key:'copy',label:'副本',children:copy?`${copy.title} · ${copy.code}`:'已选馆藏副本'},{key:'due',label:'预计应还日期',children:dayjs(today).add(30,'day').format('YYYY-MM-DD')}]}/><p className="muted">期限由服务端办理时计算；提交前会重新检查借阅资格。</p></Modal></div>
}
export default function CirculationPage() { return <><PageTitle title="借还工作台" description="核对实物，完成一次有记录的流通。"/><Tabs items={[{key:'borrow',label:'办理借出',children:<BorrowDesk/>},{key:'return',label:'归还 / 续借',children:<div className="panel"><p className="muted">搜索馆藏编号，找到对应的未归还记录后办理。</p><LoanList/></div>}]} destroyOnHidden/></> }

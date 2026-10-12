import { Alert, Empty, Spin, Typography } from 'antd';
import type { ReactNode } from 'react';
export function PageTitle({eyebrow,title,description,extra}:{eyebrow?:string;title:string;description:string;extra?:ReactNode}) { return <div className="page-heading"><div><span className="eyebrow">{eyebrow??'LIBRARY / 书间'}</span><h1>{title}</h1><p>{description}</p></div>{extra}</div> }
export function Status({error,pending}:{error:Error|null;pending:boolean}) { return error?<Alert type="error" showIcon title={error.message}/>:pending?<div className="loading"><Spin description="正在读取"/></div>:null }
export const time=(value:string|null)=>value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',dateStyle:'short',timeStyle:'short'}).format(new Date(value)):'—';
export function Mono({children}:{children:ReactNode}) { return <Typography.Text className="mono">{children}</Typography.Text> }
export function Nothing({text='暂无记录'}:{text?:string}) { return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text}/> }

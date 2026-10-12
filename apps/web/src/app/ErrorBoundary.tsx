import { Component,type ReactNode } from 'react';
import { Result,Button } from 'antd';
export class ErrorBoundary extends Component<{children:ReactNode},{failed:boolean}> {
  state={failed:false};
  static getDerivedStateFromError(){return {failed:true}}
  render(){return this.state.failed?<Result status="error" title="页面暂时无法显示" subTitle="请刷新页面重试。若持续发生，可检查浏览器控制台和后端日志。" extra={<Button type="primary" onClick={()=>window.location.reload()}>刷新页面</Button>}/>:this.props.children}
}

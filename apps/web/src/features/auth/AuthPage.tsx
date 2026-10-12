import { App, Button, Form, Input, Alert } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { write } from '../../shared/api';
import { useAuth } from '../../app/auth';
export default function AuthPage({ change = false }: { change?: boolean }) {
  const { refresh, signOut } = useAuth();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async (values: Record<string, string>) => {
    setBusy(true);
    setError('');
    try {
      if (change) {
        await write('/auth/password', values);
        await signOut();
        message.success('密码已更新，请重新登录');
        navigate('/login', { replace: true });
      } else {
        await write('/auth/login', values);
        const user = await refresh();
        navigate(
          user.mustChangePassword ? '/change-password' : user.role === 'admin' ? '/' : '/books',
          { replace: true },
        );
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="auth-screen">
      <div className="auth-story">
        <span className="wordmark">
          书间 <small>SHUJIAN</small>
        </span>
        <div>
          <span className="eyebrow">A PLACE FOR BOOKS</span>
          <h1>
            让每一本书，
            <br />
            找到下一位读者。
          </h1>
          <p>
            从一册馆藏到一次借阅，
            <br />
            把图书室的日常整理得井井有条。
          </p>
        </div>
        <span className="auth-foot">图书借阅管理 · 小型图书室</span>
        <div className="book-lines" aria-hidden="true">
          <i />
          <i />
          <i />
          <i />
          <i />
        </div>
      </div>
      <div className="auth-form">
        <span className="eyebrow">{change ? 'ACCOUNT / 账号' : 'WELCOME BACK / 欢迎回来'}</span>
        <h2>{change ? '修改密码' : '登录书间'}</h2>
        <p>
          {change
            ? '初始密码需要修改；修改后已有登录会话将失效。'
            : '使用管理员为你创建的账号登录。'}
        </p>
        {error ? <Alert type="error" showIcon title={error} style={{ marginBottom: 20 }} /> : null}
        <Form layout="vertical" onFinish={submit} requiredMark={false}>
          {change ? (
            <>
              <Form.Item
                name="oldPassword"
                label="原密码"
                rules={[{ required: true, message: '请输入原密码' }]}
              >
                <Input.Password autoComplete="current-password" maxLength={128} />
              </Form.Item>
              <Form.Item
                name="newPassword"
                label="新密码"
                rules={[
                  { required: true, message: '请输入新密码' },
                  { min: 5, max: 128, message: '请输入 5～128 个字符' },
                ]}
              >
                <Input.Password autoComplete="new-password" maxLength={128} />
              </Form.Item>
            </>
          ) : (
            <>
              <Form.Item
                name="username"
                label="用户名"
                rules={[{ required: true, message: '请输入用户名' }]}
              >
                <Input autoComplete="username" maxLength={32} />
              </Form.Item>
              <Form.Item
                name="password"
                label="密码"
                rules={[{ required: true, message: '请输入密码' }]}
              >
                <Input.Password autoComplete="current-password" maxLength={128} />
              </Form.Item>
            </>
          )}
          <Button type="primary" htmlType="submit" block size="large" loading={busy}>
            {change ? '更新密码' : '进入图书室'}
          </Button>
        </Form>
        <span className="form-foot">账号与借阅记录仅对授权用户开放。</span>
        {change ? (
          <Button type="link" onClick={() => signOut()}>
            退出登录
          </Button>
        ) : null}
      </div>
    </div>
  );
}

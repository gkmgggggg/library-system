import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Table,
  Input,
  Button,
  Space,
  Tag,
  Modal,
  Form,
  Popconfirm,
  Drawer,
  Descriptions,
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { api, query } from '../../shared/api';
import { useAction } from '../../shared/useAction';
import { PageTitle, Status, Mono } from '../../shared/ui';
import type { Reader, Page } from '../../shared/types';
import { LoanList } from '../loans/LoansPage';
export default function ReadersPage() {
  const [filter, setFilter] = useState({ q: '', page: 1, pageSize: 20 });
  const [editing, setEditing] = useState<Reader | null | undefined>();
  const [reset, setReset] = useState<Reader | null>(null);
  const [detail, setDetail] = useState<Reader | null>(null);
  const [form] = Form.useForm();
  const [resetForm] = Form.useForm();
  const { run, busy } = useAction();
  const data = useQuery({
    queryKey: ['readers', filter],
    queryFn: ({ signal }) => api<Page<Reader>>('/readers' + query(filter), { signal }),
  });
  const edit = (r: Reader | null) => {
    form.resetFields();
    if (r) form.setFieldsValue(r);
    setEditing(r);
  };
  return (
    <>
      <PageTitle
        title="读者管理"
        description="管理账号与读者资料，借阅历史始终保留。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => edit(null)}>
            创建读者
          </Button>
        }
      />
      <div className="panel">
        <div className="toolbar">
          <Input.Search
            allowClear
            placeholder="搜索姓名、读者编号或用户名"
            onSearch={(q) => setFilter({ ...filter, q, page: 1 })}
          />
        </div>
        <Status error={data.error} pending={false} />
        <Table<Reader>
          rowKey="id"
          dataSource={data.data?.items}
          loading={data.isPending}
          scroll={{ x: 1050 }}
          pagination={{
            current: filter.page,
            pageSize: filter.pageSize,
            total: data.data?.total,
            showSizeChanger: true,
            pageSizeOptions: [20, 50],
            onChange: (page, pageSize) => setFilter({ ...filter, page, pageSize }),
          }}
          columns={[
            {
              title: '读者',
              render: (_, r) => (
                <div>
                  <button className="text-button" onClick={() => setDetail(r)}>
                    {r.name}
                  </button>
                  <small className="subtext">{r.username}</small>
                </div>
              ),
            },
            { title: '编号', dataIndex: 'code', render: (v) => <Mono>{v}</Mono> },
            { title: '联系电话', dataIndex: 'phone', render: (v) => v || '—' },
            { title: '未还', dataIndex: 'activeLoans', render: (v) => `${v} / 5 册` },
            {
              title: '账号',
              dataIndex: 'enabled',
              render: (v) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag>,
            },
            {
              title: '操作',
              render: (_, r) => (
                <Space>
                  <Button type="link" onClick={() => edit(r)}>
                    编辑
                  </Button>
                  <Button
                    type="link"
                    onClick={() => {
                      resetForm.resetFields();
                      setReset(r);
                    }}
                  >
                    重置密码
                  </Button>
                  <Popconfirm
                    title={
                      r.enabled ? '停用账号？已有会话失效，管理员仍可办理归还。' : '恢复读者账号？'
                    }
                    onConfirm={() => run(`/readers/${r.id}/${r.enabled ? 'disable' : 'enable'}`)}
                  >
                    <Button type="link" danger={r.enabled}>
                      {r.enabled ? '停用' : '恢复'}
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </div>
      <Modal
        title={editing ? '编辑读者资料' : '创建读者'}
        open={editing !== undefined}
        confirmLoading={busy}
        onCancel={() => setEditing(undefined)}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            run(
              editing ? `/readers/${editing.id}` : '/readers',
              values,
              editing ? 'PATCH' : 'POST',
              () => setEditing(undefined),
            )
          }
        >
          {!editing ? (
            <>
              <Form.Item
                name="username"
                label="用户名"
                rules={[
                  { required: true },
                  { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3～32 位字母、数字或下划线' },
                ]}
              >
                <Input autoComplete="off" maxLength={32} />
              </Form.Item>
              <Form.Item
                name="password"
                label="初始密码（首次登录须修改）"
                rules={[{ required: true }, { min: 5, max: 128 }]}
              >
                <Input.Password autoComplete="new-password" maxLength={128} />
              </Form.Item>
            </>
          ) : null}
          <Form.Item name="name" label="姓名" rules={[{ required: true, whitespace: true }]}>
            <Input maxLength={50} />
          </Form.Item>
          <Form.Item name="phone" label="联系电话（可选）">
            <Input maxLength={30} />
          </Form.Item>
          <Form.Item name="note" label="备注">
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={`重置密码 · ${reset?.name}`}
        open={!!reset}
        confirmLoading={busy}
        onCancel={() => setReset(null)}
        onOk={() => resetForm.submit()}
      >
        <p className="muted">现有会话将失效，读者下次登录需再次修改密码。</p>
        <Form
          form={resetForm}
          layout="vertical"
          onFinish={(values) =>
            run(`/readers/${reset!.id}/reset-password`, values, 'POST', () => setReset(null))
          }
        >
          <Form.Item
            name="password"
            label="新初始密码"
            rules={[{ required: true }, { min: 5, max: 128 }]}
          >
            <Input.Password autoComplete="new-password" maxLength={128} />
          </Form.Item>
        </Form>
      </Modal>
      <Drawer
        title={`读者详情 · ${detail?.name}`}
        open={!!detail}
        onClose={() => setDetail(null)}
        size="large"
      >
        {detail ? (
          <>
            <Descriptions
              column={1}
              items={[
                { key: 'code', label: '读者编号', children: detail.code },
                { key: 'username', label: '用户名', children: detail.username },
                { key: 'phone', label: '联系电话', children: detail.phone || '—' },
                { key: 'note', label: '备注', children: detail.note || '—' },
              ]}
            />
            <h3>借阅历史</h3>
            <LoanList readerId={detail.id} />
          </>
        ) : null}
      </Drawer>
    </>
  );
}

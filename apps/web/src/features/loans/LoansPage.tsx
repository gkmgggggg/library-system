import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Table,
  Input,
  Select,
  Tag,
  Space,
  Button,
  Modal,
  Form,
  Switch,
  Popconfirm,
  DatePicker,
} from 'antd';
import { api, query } from '../../shared/api';
import { useAction } from '../../shared/useAction';
import { useAuth } from '../../app/auth';
import { PageTitle, Status, time, Mono } from '../../shared/ui';
import type { Loan, Page } from '../../shared/types';
export function LoanList({ readerId }: { readerId?: string }) {
  const admin = useAuth().user?.role === 'admin';
  const { run, busy } = useAction();
  const [returning, setReturning] = useState<Loan | null>(null);
  const [form] = Form.useForm();
  const [filter, setFilter] = useState({
    q: '',
    state: 'all',
    timing: 'all',
    page: 1,
    pageSize: 20,
    from: '',
    to: '',
  });
  const data = useQuery({
    queryKey: ['loans', readerId, admin, filter],
    queryFn: ({ signal }) =>
      api<Page<Loan>>((admin ? '/loans' : '/me/loans') + query({ ...filter, readerId }), {
        signal,
      }),
  });
  return (
    <>
      <div className="toolbar">
        <Input.Search
          placeholder="搜索书名、馆藏编号或读者"
          allowClear
          onSearch={(q) => setFilter({ ...filter, q, page: 1 })}
        />
        <Select
          value={filter.state}
          onChange={(state) => setFilter({ ...filter, state, page: 1 })}
          options={[
            { value: 'all', label: '全部借阅' },
            { value: 'active', label: '未归还' },
            { value: 'returned', label: '已归还' },
          ]}
        />
        <Select
          value={filter.timing}
          onChange={(timing) => setFilter({ ...filter, timing, page: 1 })}
          options={[
            { value: 'all', label: '全部期限' },
            { value: 'dueSoon', label: '即将到期（3 天内）' },
            { value: 'overdue', label: '逾期未还' },
            { value: 'returnedOverdue', label: '逾期归还' },
          ]}
        />
        <DatePicker.RangePicker
          onChange={(dates) =>
            setFilter({
              ...filter,
              from: dates?.[0]?.format('YYYY-MM-DD') ?? '',
              to: dates?.[1]?.format('YYYY-MM-DD') ?? '',
              page: 1,
            })
          }
        />
      </div>
      <Status error={data.error} pending={false} />
      <Table<Loan>
        rowKey="id"
        dataSource={data.data?.items}
        loading={data.isPending}
        scroll={{ x: 1100 }}
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
            title: '图书 / 副本',
            render: (_, l) => (
              <div>
                <strong>{l.title}</strong>
                <small className="subtext">
                  <Mono>{l.copyCode}</Mono>
                </small>
              </div>
            ),
          },
          ...(admin
            ? [
                {
                  title: '读者',
                  render: (_: unknown, l: Loan) => (
                    <div>
                      {l.readerName}
                      <small className="subtext">{l.readerCode}</small>
                    </div>
                  ),
                },
              ]
            : []),
          { title: '借出时间', dataIndex: 'borrowedAt', render: time },
          {
            title: '应还日期',
            dataIndex: 'dueDate',
            render: (v, l) => (
              <span className={!l.returnedAt && l.overdueDays > 0 ? 'overdue-text' : ''}>{v}</span>
            ),
          },
          { title: '归还时间', dataIndex: 'returnedAt', render: time },
          {
            title: '状态',
            render: (_, l) => (
              <Space orientation="vertical" size={0}>
                <Tag color={l.returnedAt ? 'default' : l.overdueDays > 0 ? 'red' : 'green'}>
                  {l.returnedAt
                    ? '已归还'
                    : l.overdueDays > 0
                      ? `逾期 ${l.overdueDays} 天`
                      : '借阅中'}
                </Tag>
                {l.returnedAt && l.overdueDays > 0 ? (
                  <small className="overdue-text">逾期归还 {l.overdueDays} 天</small>
                ) : null}
                {l.renewalCount ? <small className="muted">已续借</small> : null}
              </Space>
            ),
          },
          {
            title: '操作',
            render: (_, l) => (
              <Space>
                {!l.returnedAt ? (
                  <>
                    <Popconfirm
                      title="续借？从当前应还日期再延长 30 天。"
                      onConfirm={() => run(`/loans/${l.id}/renew`)}
                    >
                      <Button
                        type="link"
                        disabled={busy || l.renewalCount > 0 || l.overdueDays > 0}
                      >
                        续借
                      </Button>
                    </Popconfirm>
                    {admin ? (
                      <Button
                        type="link"
                        disabled={busy}
                        onClick={() => {
                          form.resetFields();
                          setReturning(l);
                        }}
                      >
                        归还
                      </Button>
                    ) : null}
                  </>
                ) : (
                  <span className="muted">已完成</span>
                )}
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title={`归还确认 · ${returning?.title}`}
        open={!!returning}
        confirmLoading={busy}
        onCancel={() => setReturning(null)}
        onOk={() => form.submit()}
      >
        <p>
          请核验实物与馆藏编号 <Mono>{returning?.copyCode}</Mono>，读者为 {returning?.readerName}。
        </p>
        <Form
          form={form}
          layout="vertical"
          initialValues={{ damaged: false }}
          onFinish={(values) =>
            run(`/loans/${returning!.id}/return`, values, 'POST', () => setReturning(null))
          }
        >
          <Form.Item name="damaged" label="实物破损，不可继续借出" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="note" label="归还备注（破损时必填）">
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
export default function LoansPage() {
  const admin = useAuth().user?.role === 'admin';
  return (
    <>
      <PageTitle
        title={admin ? '借阅记录' : '我的借阅'}
        description={
          admin ? '查看流通记录，跟进到期与逾期图书。' : '查看应还日期，在期限内为自己的图书续借。'
        }
      />
      <div className="panel">
        <LoanList />
      </div>
    </>
  );
}

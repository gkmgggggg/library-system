import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Table, Modal, Form, Input, Space, Popconfirm } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { api } from '../../shared/api';
import { useAction } from '../../shared/useAction';
import { PageTitle, Status } from '../../shared/ui';
import type { Category } from '../../shared/types';
export default function CategoriesPage() {
  const list = useQuery({
    queryKey: ['categories'],
    queryFn: () => api<Category[]>('/categories'),
  });
  const [editing, setEditing] = useState<Category | null | undefined>();
  const [form] = Form.useForm();
  const { run, busy } = useAction();
  const edit = (c: Category | null) => {
    form.resetFields();
    if (c) form.setFieldsValue(c);
    setEditing(c);
  };
  return (
    <>
      <PageTitle
        title="分类管理"
        description="清晰的单层分类，帮助读者快速找到感兴趣的书。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => edit(null)}>
            添加分类
          </Button>
        }
      />
      <div className="panel">
        <Status error={list.error} pending={false} />
        <Table<Category>
          rowKey="id"
          dataSource={list.data}
          loading={list.isPending}
          columns={[
            { title: '分类名称', dataIndex: 'name' },
            { title: '关联书目（含归档）', dataIndex: 'bookCount' },
            {
              title: '操作',
              render: (_, c) => (
                <Space>
                  <Button type="link" onClick={() => edit(c)}>
                    改名
                  </Button>
                  <Popconfirm
                    title="删除分类？仍有关联书目时不能删除。"
                    onConfirm={() => run(`/categories/${c.id}`, {}, 'DELETE')}
                  >
                    <Button danger type="link">
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
          pagination={false}
        />
      </div>
      <Modal
        title={editing ? '修改分类' : '添加分类'}
        open={editing !== undefined}
        onCancel={() => setEditing(undefined)}
        onOk={() => form.submit()}
        confirmLoading={busy}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={(values) =>
            run(
              editing ? `/categories/${editing.id}` : '/categories',
              values,
              editing ? 'PATCH' : 'POST',
              () => setEditing(undefined),
            )
          }
        >
          <Form.Item name="name" label="名称" rules={[{ required: true, whitespace: true }]}>
            <Input maxLength={30} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}

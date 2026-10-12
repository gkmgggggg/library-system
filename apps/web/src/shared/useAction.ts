import { App } from 'antd';
import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { write } from './api';
export function useAction() {
  const { message } = App.useApp();
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const run = async (path: string, body: unknown = {}, method = 'POST', after?: () => void) => {
    if (busy) return;
    setBusy(true);
    try {
      await write(path, body, method);
      message.success('操作已完成');
      await client.invalidateQueries({ predicate: (q) => q.queryKey[0] !== 'me' });
      after?.();
      return true;
    } catch (e) {
      message.error((e as Error).message);
      return false;
    } finally {
      setBusy(false);
    }
  };
  return { run, busy };
}

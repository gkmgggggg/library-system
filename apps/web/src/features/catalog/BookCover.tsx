import { useState } from 'react';

export function BookCover({
  title,
  url,
  large = false,
}: {
  title: string;
  url: string | null;
  large?: boolean;
}) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  if (url && failedUrl !== url) {
    return (
      <img
        className={`book-cover${large ? ' large' : ''}`}
        src={url}
        alt=""
        width={large ? 80 : 40}
        height={large ? 104 : 52}
        loading="lazy"
        decoding="async"
        onError={() => setFailedUrl(url)}
      />
    );
  }
  return (
    <span className={`book-marker${large ? ' large' : ''}`} aria-hidden="true">
      {title.slice(0, 1)}
    </span>
  );
}

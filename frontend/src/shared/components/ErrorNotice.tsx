export default function ErrorNotice({
  message,
  retry,
}: {
  message: string;
  retry: () => void;
}) {
  if (!message) return null;
  return (
    <div role="alert" className="error">
      {message}
      <button onClick={retry}>Retry</button>
    </div>
  );
}

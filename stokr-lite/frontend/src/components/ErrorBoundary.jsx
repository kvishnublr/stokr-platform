import React from 'react';

class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null, errorInfo: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true };
  }

  componentDidCatch(error, errorInfo) {
    this.setState({ error, errorInfo });
    console.error("ErrorBoundary caught an error:", error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="p-5 m-5 bg-red-50 border border-red-200 rounded-lg text-red-800 font-mono text-sm whitespace-pre-wrap break-all">
          <h2 className="font-bold text-lg mb-2">React Runtime Crash</h2>
          <details className="whitespace-pre-wrap">
            <summary className="cursor-pointer font-bold mb-2 text-red-600">Click to view Stack Trace</summary>
            <div className="bg-white p-3 rounded border border-red-100 mt-2 text-xs">
              {this.state.error && this.state.error.toString()}
              <br/><br/>
              {this.state.errorInfo && this.state.errorInfo.componentStack}
            </div>
          </details>
        </div>
      );
    }
    return this.props.children;
  }
}

export default ErrorBoundary;

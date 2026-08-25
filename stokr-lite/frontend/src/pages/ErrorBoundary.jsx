import React from 'react';
export class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }
  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }
  componentDidCatch(error, errorInfo) {
    console.error("ErrorBoundary caught error:", error, errorInfo);
  }
  render() {
    if (this.state.hasError) {
      return (
        <div className="p-4 bg-red-50 text-red-600 border border-red-200 rounded-lg whitespace-pre-wrap overflow-auto max-h-[300px]">
          <strong>Component Crash:</strong><br/>
          {this.state.error && this.state.error.toString()}
        </div>
      );
    }
    return this.props.children;
  }
}
